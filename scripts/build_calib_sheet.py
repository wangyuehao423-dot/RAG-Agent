"""生成 30 条人工校准表（单页 HTML，可点选、可复制结果）。

做这件事的原因：RAGAS 的裁判是一个大模型，它会偏（偏爱长答案、偏爱自己风格的答案、
位置敏感）。所以「忠实度 0.795」这个数在被人验证过之前，只是一个模型的自评。
抽 30 条人工判对错，跟 RAGAS 的判定比对，一致率达标才说明这个裁判的分数能采信。

判定口径（写死在这里，避免事后改口径凑一致率）：
  忠实度 >= 0.7  且  答案相关性 >= 0.7  且  上下文精确率 >= 0.5   ->  RAGAS 判「通过」
"""
import html
import json
import random
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
INPUT = ROOT / "data" / "eval" / "eval-20261117-120646.jsonl"
CACHE_DIR = ROOT / "data" / "eval" / ".cache"
STEM = INPUT.stem
N_SAMPLE, N_PICK, SEED = 60, 30, 42


def load_rows():
    rows = []
    for line in INPUT.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        r = json.loads(line)
        ans = (r.get("answer") or "").strip()
        ctxs = [c for c in (r.get("contexts") or []) if (c or "").strip()]
        if not ans or not ctxs:
            continue
        rows.append(r)
    if N_SAMPLE < len(rows):
        idx = sorted(random.Random(SEED).sample(range(len(rows)), N_SAMPLE))
        rows = [rows[i] for i in idx]
    return rows


def load_scores():
    out = {}
    for n in ("faithfulness", "answer_relevancy", "context_precision", "context_recall"):
        p = CACHE_DIR / f"{STEM}-n{N_SAMPLE}-{n}.json"
        out[n] = json.loads(p.read_text(encoding="utf-8")) if p.exists() else []
    return out


def fmt(v):
    return "—" if v is None else f"{v:.2f}"


def main():
    rows = load_rows()
    scores = load_scores()

    # 固定再抽一次，保证同一个 seed 下每次生成的清单是同一批题
    pick = sorted(random.Random(SEED + 1).sample(range(len(rows)), min(N_PICK, len(rows))))

    cards = []
    for seq, i in enumerate(pick, 1):
        r = rows[i]
        f = scores["faithfulness"][i] if i < len(scores["faithfulness"]) else None
        ar = scores["answer_relevancy"][i] if i < len(scores["answer_relevancy"]) else None
        cp = scores["context_precision"][i] if i < len(scores["context_precision"]) else None
        cr = scores["context_recall"][i] if i < len(scores["context_recall"]) else None

        passed = (f is not None and ar is not None and cp is not None
                  and f >= 0.7 and ar >= 0.7 and cp >= 0.5)
        verdict = "通过" if passed else "不通过"

        ctxs = (r.get("contexts") or [])[:2]
        ctx_html = "".join(
            f"<div class='ctx'><span class='ctxno'>片段 {k + 1}</span>"
            f"{html.escape(c[:260])}{'…' if len(c) > 260 else ''}</div>"
            for k, c in enumerate(ctxs)
        )

        cards.append(f"""
<div class="card" data-seq="{seq}" data-ragas="{'pass' if passed else 'fail'}">
  <div class="head">
    <span class="seq">#{seq}</span>
    <span class="q">{html.escape(r.get('question', ''))}</span>
    <span class="badge {'b-pass' if passed else 'b-fail'}">RAGAS 判定：{verdict}</span>
  </div>
  <div class="scores">忠实度 {fmt(f)} · 相关性 {fmt(ar)} · 精确率 {fmt(cp)} · 召回率 {fmt(cr)}</div>
  <div class="row"><span class="lbl">标准答案</span><span class="gt">{html.escape((r.get('ground_truth') or '')[:300])}</span></div>
  <div class="row"><span class="lbl">模型答案</span><span class="ans">{html.escape((r.get('answer') or '')[:600])}</span></div>
  <div class="row"><span class="lbl">检索片段</span><div class="ctxs">{ctx_html}</div></div>
  <div class="vote">
    <span class="lbl">人工判定</span>
    <button class="btn ok" onclick="vote({seq},'ok',this)">答对</button>
    <button class="btn no" onclick="vote({seq},'no',this)">答错</button>
  </div>
</div>""")

    page = f"""<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<title>RAGAS 裁判校准表 · 30 条</title>
<style>
:root{{--bg:#fff;--fg:#1a1a1a;--dim:#6b7280;--line:#e5e7eb;--ok:#067647;--no:#b42318;--soft:#f9fafb;}}
*{{box-sizing:border-box}}
body{{margin:0;background:var(--bg);color:var(--fg);
font:15px/1.65 -apple-system,"PingFang SC","Microsoft YaHei",sans-serif}}
.wrap{{max-width:900px;margin:0 auto;padding:28px 20px 120px}}
h1{{font-size:21px;margin:0 0 6px}}
.sub{{color:var(--dim);font-size:13px;margin-bottom:18px}}
.rule{{background:var(--soft);border:1px solid var(--line);border-radius:8px;
padding:12px 14px;font-size:13px;color:#374151;margin-bottom:22px}}
.bar{{position:fixed;left:0;right:0;bottom:0;background:#fff;border-top:1px solid var(--line);
padding:12px 20px;display:flex;gap:16px;align-items:center;font-size:14px}}
.bar b{{font-variant-numeric:tabular-nums}}
.btn{{border:1px solid var(--line);background:#fff;border-radius:6px;padding:6px 14px;
cursor:pointer;font-size:14px;color:var(--fg)}}
.btn:hover{{background:#f3f4f6}}
.card{{border:1px solid var(--line);border-radius:10px;padding:14px 16px;margin-bottom:14px}}
.card.done-ok{{border-color:#abefc6;background:#f6fef9}}
.card.done-no{{border-color:#fecdca;background:#fffbfa}}
.head{{display:flex;gap:10px;align-items:baseline;flex-wrap:wrap;margin-bottom:6px}}
.seq{{color:var(--dim);font-size:13px;font-variant-numeric:tabular-nums}}
.q{{font-weight:600;flex:1;min-width:240px}}
.badge{{font-size:12px;padding:2px 8px;border-radius:99px}}
.b-pass{{background:#ecfdf3;color:var(--ok);border:1px solid #abefc6}}
.b-fail{{background:#fef3f2;color:var(--no);border:1px solid #fecdca}}
.scores{{color:var(--dim);font-size:12.5px;margin-bottom:10px;font-variant-numeric:tabular-nums}}
.row{{display:flex;gap:10px;margin-bottom:8px;font-size:14px}}
.lbl{{flex:0 0 62px;color:var(--dim);font-size:12.5px;padding-top:2px}}
.gt,.ans{{flex:1;white-space:pre-wrap;word-break:break-word}}
.ans{{background:var(--soft);border-radius:6px;padding:8px 10px}}
.ctxs{{flex:1}}
.ctx{{background:var(--soft);border-radius:6px;padding:7px 10px;margin-bottom:6px;
font-size:13px;color:#374151;white-space:pre-wrap;word-break:break-word}}
.ctxno{{display:inline-block;font-size:11.5px;color:var(--dim);margin-right:6px}}
.vote{{display:flex;gap:10px;align-items:center;margin-top:10px;
padding-top:10px;border-top:1px dashed var(--line)}}
.btn.ok.sel{{background:var(--ok);color:#fff;border-color:var(--ok)}}
.btn.no.sel{{background:var(--no);color:#fff;border-color:var(--no)}}
</style></head><body><div class="wrap">
<h1>RAGAS 裁判校准表</h1>
<div class="sub">从 60 条评测样本里固定抽 30 条 · 判完把底部那串结果发我</div>
<div class="rule">
判定口径：<b>忠实度 ≥ 0.7 且 答案相关性 ≥ 0.7 且 上下文精确率 ≥ 0.5</b> 记为「通过」，否则「不通过」。<br>
你要判的是：<b>这条答案有没有真正回答问题、有没有编造</b>。判完系统会算你和 RAGAS 的一致率（阈值 90%），低于 90% 说明裁判本身要修。
</div>
{''.join(cards)}
</div>
<div class="bar">
  <span>已判 <b id="cnt">0</b>/30</span>
  <span>一致 <b id="agree">0</b></span>
  <span>一致率 <b id="rate">—</b></span>
  <button class="btn" onclick="dump()">复制结果</button>
  <span id="hint" style="color:var(--dim);font-size:12.5px"></span>
</div>
<script>
const votes={{}};
function vote(seq,kind,btn){{
  votes[seq]=kind;
  const card=document.querySelector('[data-seq="'+seq+'"]');
  card.querySelectorAll('.btn').forEach(b=>b.classList.remove('sel'));
  btn.classList.add('sel');
  card.classList.remove('done-ok','done-no');
  card.classList.add(kind==='ok'?'done-ok':'done-no');
  render();
}}
function render(){{
  const ks=Object.keys(votes);
  document.getElementById('cnt').textContent=ks.length;
  let agree=0;
  ks.forEach(k=>{{
    const card=document.querySelector('[data-seq="'+k+'"]');
    const ragasPass=card.dataset.ragas==='pass';
    if((votes[k]==='ok')===ragasPass) agree++;
  }});
  document.getElementById('agree').textContent=agree;
  document.getElementById('rate').textContent=ks.length?Math.round(agree/ks.length*100)+'%':'—';
}}
function dump(){{
  const s=Object.keys(votes).sort((a,b)=>a-b).map(k=>k+':'+votes[k]).join(',');
  navigator.clipboard.writeText(s).then(()=>{{
    document.getElementById('hint').textContent='已复制，粘贴给我即可';
  }},()=>{{
    document.getElementById('hint').textContent=s;
  }});
}}
</script></body></html>"""

    out = ROOT / "data" / "eval" / "calibration-30.html"
    out.write_text(page, encoding="utf-8")
    print(f"[OK] 生成 {out}")
    print(f"     抽了 {len(pick)} 条，其中 RAGAS 判通过 "
          f"{sum(1 for c in cards if 'b-pass' in c)} 条")


if __name__ == "__main__":
    main()
