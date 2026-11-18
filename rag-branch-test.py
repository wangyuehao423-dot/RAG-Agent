"""RAG Agent branch regression. Pure stdlib, proxy bypassed (HTTP_PROXY poisons localhost).

Covers: upload / dedupe(processing + done) / 400 type guards / ingest to DONE /
in-domain answer with citation / multi-turn anaphora / out-of-domain refusal /
SSE streaming / threshold / drift / calibrate / delete 400-404-200 cascade /
conversation messages + clear. Eval export is an offline job (RUN_EVAL=1).
"""
import json
import os
import random
import sys
import time
import uuid
import urllib.request
import urllib.error

BASE = "http://127.0.0.1:8080"
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))

PASS = 0
FAIL = 0
T0 = time.time()


def step(msg):
    print("[%6.1fs] %s" % (time.time() - T0, msg), flush=True)


def check(name, actual, expected, extra=""):
    global PASS, FAIL
    ok = actual == expected
    if ok:
        PASS += 1
        step("%s -> %s [OK] %s" % (name, actual, extra))
    else:
        FAIL += 1
        step("%s -> %s [UNEXPECTED expect %s] %s" % (name, actual, expected, extra))
    return ok


def call(method, path, body=None, timeout=300):
    url = BASE + path
    data = None
    headers = {}
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with OPENER.open(req, timeout=timeout) as resp:
            payload, code = resp.read(), resp.status
    except urllib.error.HTTPError as err:
        payload, code = err.read(), err.code
    text = payload.decode("utf-8", "replace")
    try:
        return code, json.loads(text) if text.strip() else None
    except Exception:
        return code, text


def upload(filename, content):
    boundary = "----ragboundary" + uuid.uuid4().hex
    body = b""
    body += ("--%s\r\n" % boundary).encode()
    body += ('Content-Disposition: form-data; name="file"; filename="%s"\r\n' % filename).encode()
    body += b"Content-Type: application/octet-stream\r\n\r\n"
    body += content
    body += ("\r\n--%s--\r\n" % boundary).encode()
    req = urllib.request.Request(
        BASE + "/api/documents", data=body,
        headers={"Content-Type": "multipart/form-data; boundary=" + boundary},
        method="POST")
    try:
        with OPENER.open(req, timeout=300) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as err:
        raw = err.read().decode("utf-8", "replace")
        try:
            return err.code, json.loads(raw)
        except Exception:
            return err.code, raw


def docs():
    return call("GET", "/api/documents")[1]


step("=== RAG Agent branch regression ===")

# ---- 0 clean leftovers from earlier runs, then take baseline
for row in docs():
    if row.get("name") == "branch-test.md":
        call("DELETE", "/api/documents/%s" % row["id"])
        step("0 cleanup leftover %s" % row["id"])
baseline = len(docs())
step("0 baseline documents=%d" % baseline)

BATCH = random.randint(1000, 9999)
MARKER = "GX-%d" % BATCH
DOC_TEXT = (
    "# 灰度发布流程说明\n\n"
    "## 批次编号规则\n\n"
    "所有灰度批次必须携带唯一批次号，格式为 GX 加四位数字，当前示例批次号为 %s。\n"
    "批次号由发布平台在创建批次时自动生成，不允许人工填写。\n\n"
    "## 回滚条件\n\n"
    "当错误率连续 5 分钟超过 1.5%% 时必须立即回滚，回滚窗口最长为 37 分钟。\n"
) % MARKER

# ---- 1 upload
code, up = upload("branch-test.md", DOC_TEXT.encode())
check("1 upload new doc", code, 202, json.dumps(up, ensure_ascii=False)[:90])
doc_id = up.get("documentId") if isinstance(up, dict) else None
if not doc_id:
    step("no documentId -> abort")
    sys.exit(1)

# ---- 2 dedupe while still PROCESSING -> 202 with deduped=true
code, up2 = upload("branch-test.md", DOC_TEXT.encode())
check("2 dedupe while processing", (code, (up2 or {}).get("deduped")), (202, True),
      "sameId=%s" % str((up2 or {}).get("documentId")) == str(doc_id))

# ---- 3 wait for DONE
status = None
chunks = 0
for _ in range(60):
    row = next((r for r in docs() if str(r.get("id")) == str(doc_id)), None)
    if row and row.get("status") in ("DONE", "FAILED"):
        status = row.get("status")
        chunks = row.get("chunk_count") or 0
        break
    time.sleep(2)
check("3 ingestion finished", status, "DONE", "chunks=%s" % chunks)

# ---- 4 dedupe after DONE -> 200 (the work is already finished)
code, up3 = upload("branch-test.md", DOC_TEXT.encode())
check("4 dedupe after DONE", (code, (up3 or {}).get("deduped")), (200, True),
      "sameId=%s" % str((up3 or {}).get("documentId")) == str(doc_id))

# ---- 5-7 rejection branches
code, body = upload("empty.md", b"")
check("5 empty file", code, 400, str(body)[:60])
code, body = upload("virus.exe", b"MZ\x90\x00fake")
check("6 .exe upload", code, 400, str(body)[:60])
code, body = upload("noext", b"hello world")
check("7 no-extension upload", code, 400, str(body)[:60])

# ---- 8 in-domain question must cite the fresh doc
cid = "branch-" + uuid.uuid4().hex[:8]
code, ans = call("POST", "/api/chat", {"question": "灰度批次号的格式是什么样的？", "conversationId": cid})
answer = (ans or {}).get("answer") or ""
check("8 in-domain answer cites new doc", MARKER in answer, True,
      "refused=%s sources=%s" % ((ans or {}).get("refused"), len((ans or {}).get("sources") or [])))

# ---- 9 multi-turn anaphora (pronoun-free follow-up in same conversation)
code, ans2 = call("POST", "/api/chat", {"question": "那回滚窗口最长是多久？", "conversationId": cid})
a2 = (ans2 or {}).get("answer") or ""
check("9 follow-up in same conversation", "37" in a2, True, "refused=%s" % (ans2 or {}).get("refused"))

# ---- 10 out-of-domain must be refused, not hallucinated
code, ans3 = call("POST", "/api/chat",
                  {"question": "如何申请办理护照？需要哪些材料和流程？", "conversationId": "branch-" + uuid.uuid4().hex[:8]})
check("10 out-of-domain refused", (ans3 or {}).get("refused"), True,
      "sources=%s answer=%s" % (len((ans3 or {}).get("sources") or []), ((ans3 or {}).get("answer") or "")[:40]))

# ---- 11 SSE streaming
req = urllib.request.Request(
    BASE + "/api/chat/stream",
    data=json.dumps({"question": "发布回滚的错误率阈值是多少？",
                     "conversationId": "branch-" + uuid.uuid4().hex[:8]}).encode(),
    headers={"Content-Type": "application/json", "Accept": "text/event-stream"},
    method="POST")
texts, done, err = 0, False, None
try:
    with OPENER.open(req, timeout=300) as resp:
        for rawline in resp:
            line = rawline.decode("utf-8", "replace").strip()
            if line.startswith("event:"):
                name = line.split(":", 1)[1].strip()
                if name == "text":
                    texts += 1
                elif name == "done":
                    done = True
                    break
                elif name == "error":
                    err = "error event"
                    break
except Exception as exc:
    err = repr(exc)[:80]
check("11 stream text+done", (texts > 0, done), (True, True), "textEvents=%d err=%s" % (texts, err))

# ---- 12 threshold
code, th = call("GET", "/api/retrieval/threshold")
check("12 threshold", code, 200,
      "similarity=%s lexical=%s sample=%s fp=%s" % (
          (th or {}).get("similarity"), (th or {}).get("lexical"),
          (th or {}).get("sampleSize"), ((th or {}).get("corpusFingerprint") or "")[:8]))

# ---- 13 drift
code, dr = call("GET", "/api/retrieval/drift")
check("13 drift status", code, 200,
      "samples=%s refusal=%s p90=%s thr=%s verdict=%s" % (
          (dr or {}).get("samples"), (dr or {}).get("refusalRate"),
          (dr or {}).get("p90Similarity"), (dr or {}).get("currentThreshold"),
          (dr or {}).get("verdict")))

# ---- 14 calibrate
code, cal = call("POST", "/api/retrieval/calibrate", {})
check("14 calibrate", code, 200,
      "similarity=%s lexical=%s sample=%s enough=%s" % (
          (cal or {}).get("similarity"), (cal or {}).get("lexical"),
          (cal or {}).get("sampleSize"), (cal or {}).get("sampleEnough")))

# ---- 15-17 delete branches
code, body = call("DELETE", "/api/documents/%s" % uuid.uuid4())
check("15 delete missing doc", code, 404, str(body)[:60])
code, body = call("DELETE", "/api/documents/not-a-uuid")
check("16 delete invalid id", code, 400, str(body)[:60])
code, dele = call("DELETE", "/api/documents/%s" % doc_id)
check("17 delete test doc", code, 200, json.dumps(dele, ensure_ascii=False)[:80])

# ---- 18 cascade gone: no chunks left behind
code, gone = call("GET", "/api/documents/%s/content" % doc_id)
check("18 deleted doc no longer readable", code in (400, 404, 500), True, "code=%s" % code)
check("19 document count back to baseline", len(docs()), baseline)

# ---- 20-22 conversation endpoints
code, convs = call("GET", "/api/conversations")
check("20 conversation list", code, 200, "count=%s" % (len(convs) if isinstance(convs, list) else -1))
code, msgs = call("GET", "/api/conversations/%s/messages" % cid)
check("21 conversation messages", code, 200, "count=%s" % (len(msgs) if isinstance(msgs, list) else -1))
code, cleared = call("DELETE", "/api/conversations/%s" % cid)
check("22 clear conversation", code, 200, str(cleared)[:60])

# ---- 23 eval export (offline job, documented as 10+ minutes) - opt-in
if os.environ.get("RUN_EVAL") == "1":
    step("23 eval export start (slow offline job)...")
    code, ev = call("POST", "/api/eval/export", {}, timeout=3600)
    check("23 eval export", code, 200,
          "total=%s exported=%s refused=%s failed=%s" % (
              (ev or {}).get("total"), (ev or {}).get("exported"),
              (ev or {}).get("refused"), (ev or {}).get("failed")))
else:
    step("23 eval export SKIPPED (RUN_EVAL=1 to run)")

print("")
print("===== RAG BRANCH DONE total=%.1fs pass=%d fail=%d =====" % (time.time() - T0, PASS, FAIL))
