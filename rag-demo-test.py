"""Demo-path regression for RAG Agent, measured the way a visitor experiences it.

Budgets (what makes a demo feel broken if exceeded):
  upload ack        <= 3s    (button must react immediately)
  ingest to DONE    <= 40s
  ask (non-stream)  <= 60s   (chat + refusal-check, two model calls in series)
  stream 1st token  <= 30s   (upstream floor: bare GLM TTFT is 7-8s on a short
                              prompt, 15-20s once ~700 chars of context is fed in)
  stream complete   <= 90s

Why these numbers: they are set just above what the upstream gateway can actually
do (measured with curl directly against tokenx24, no app in the path), so a breach
means our own code regressed - not that the model was slow that minute.
"""
import json
import os
import random
import sys
import time
import urllib.error
import urllib.request
import uuid

BASE = "http://127.0.0.1:8080"
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))
BUDGET = {"upload_ack": 3.0, "ingest": 40.0, "ask": 60.0, "first_token": 30.0, "stream_done": 90.0}
problems = []


def call(method, path, body=None, timeout=300):
    data = json.dumps(body).encode() if body is not None else None
    headers = {"Content-Type": "application/json"} if body is not None else {}
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with OPENER.open(req, timeout=timeout) as resp:
            raw, code = resp.read().decode("utf-8", "replace"), resp.status
    except urllib.error.HTTPError as err:
        raw, code = err.read().decode("utf-8", "replace"), err.code
    try:
        return code, (json.loads(raw) if raw.strip() else None)
    except Exception:
        return code, raw


def upload(name, content):
    boundary = "----demo" + uuid.uuid4().hex
    body = ("--%s\r\n" % boundary).encode()
    body += ('Content-Disposition: form-data; name="file"; filename="%s"\r\n' % name).encode()
    body += b"Content-Type: text/markdown\r\n\r\n" + content + b"\r\n"
    body += ("--%s--\r\n" % boundary).encode()
    req = urllib.request.Request(BASE + "/api/documents", data=body,
                                 headers={"Content-Type": "multipart/form-data; boundary=" + boundary},
                                 method="POST")
    try:
        with OPENER.open(req, timeout=120) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as err:
        return err.code, err.read().decode("utf-8", "replace")[:120]


def stream(question):
    """Return (first_token_seconds, done_seconds, text_events, error)."""
    req = urllib.request.Request(
        BASE + "/api/chat/stream",
        data=json.dumps({"question": question, "conversationId": "demo-" + uuid.uuid4().hex[:6]}).encode(),
        headers={"Content-Type": "application/json", "Accept": "text/event-stream"},
        method="POST")
    started = time.time()
    first = None
    texts = 0
    err = None
    try:
        with OPENER.open(req, timeout=180) as resp:
            for rawline in resp:
                line = rawline.decode("utf-8", "replace").strip()
                if not line.startswith("event:"):
                    continue
                name = line.split(":", 1)[1].strip()
                if name == "text":
                    texts += 1
                    if first is None:
                        first = time.time() - started
                elif name == "done":
                    return first, time.time() - started, texts, None
                elif name == "error":
                    err = "error event"
                    break
    except Exception as exc:
        err = repr(exc)[:60]
    return first, time.time() - started, texts, err


def run_round(idx):
    t0 = time.time()
    rows = []

    def mark(name, ok, elapsed, budget_key=None, extra=""):
        if budget_key and elapsed > BUDGET[budget_key]:
            ok = False
            extra += " (超预算 %.0fs)" % BUDGET[budget_key]
        rows.append((name, ok, elapsed, extra))
        print("  [%s] %-24s %6.1fs  %s" % ("OK " if ok else "BAD", name, elapsed, extra), flush=True)
        if not ok:
            problems.append("R%d %s %s" % (idx, name, extra))

    batch = random.randint(1000, 9999)
    marker = "ZX-%d" % batch
    text = (
        "# 值班手册\n\n## 告警升级规则\n\n一级告警必须在 %d 分钟内电话通知值班经理，升级标记为 %s。\n"
        "值班交接时必须核对本批次标记，缺失标记视为交接未完成。\n" % (batch % 40 + 5, marker)
    ).encode()

    s = time.time()
    code, up = upload("demo-runbook.md", text)
    mark("1 上传文档", code == 202 and (up or {}).get("documentId"), time.time() - s, "upload_ack",
         "HTTP %s" % code)
    if code != 202:
        return rows
    doc_id = up["documentId"]

    s = time.time()
    status = None
    chunks = None
    for _ in range(20):
        code, docs = call("GET", "/api/documents")
        row = next((d for d in (docs or []) if str(d.get("id")) == str(doc_id)), None)
        if row and row.get("status") in ("DONE", "FAILED"):
            status = row.get("status")
            chunks = row.get("chunk_count")
            break
        time.sleep(1)
    mark("2 解析入库到 DONE", status == "DONE", time.time() - s, "ingest", "chunks=%s" % chunks)

    s = time.time()
    code, ans = call("POST", "/api/chat", {"question": "一级告警要多久内通知值班经理？", "conversationId": "demo-q1"})
    elapsed = time.time() - s
    answer = (ans or {}).get("answer") or ""
    mark("3 提问出答案", code == 200 and bool(answer) and not (ans or {}).get("refused"), elapsed, "ask",
         "%.0f 字 引用=%d" % (len(answer), len((ans or {}).get("sources") or [])))

    first, done, texts, err = stream("值班交接要核对什么标记？")
    mark("4 流式首字", first is not None, first or 99.0, "first_token", "events=%d err=%s" % (texts, err))
    mark("5 流式完成", err is None and texts > 0, done, "stream_done", "events=%d" % texts)

    s = time.time()
    code, convs = call("GET", "/api/conversations")
    mark("6 会话列表", code == 200, time.time() - s, None, "count=%s" % (len(convs) if isinstance(convs, list) else "?"))

    s = time.time()
    code, dele = call("DELETE", "/api/documents/%s" % doc_id)
    mark("7 删除文档", code == 200, time.time() - s, None, json.dumps(dele, ensure_ascii=False)[:50])

    print("  ---- 本轮总耗时 %.1fs ----" % (time.time() - t0), flush=True)
    return rows


rounds = int(sys.argv[1]) if len(sys.argv) > 1 else 3
print("=== RAG 演示链路 x%d 轮 ===" % rounds)
for i in range(1, rounds + 1):
    print("\n----- round %d -----" % i, flush=True)
    try:
        run_round(i)
    except Exception as exc:
        problems.append("R%d 异常 %r" % (i, exc))
        print("  !! 异常: %r" % (exc,), flush=True)

print("\n===== 汇总 =====")
if problems:
    print("发现问题 %d 条：" % len(problems))
    for p in problems:
        print(" -", p)
else:
    print("全部通过：每一步都在预算内返回")
