"""量化两类查询在向量通道上的 top1 相似度分布。

用途：验证「P90 阈值是否真的把相关/无关两类分开」这句话。
- 相关侧：187 条评测问题（知识库里确实有答案）
- 无关侧：内置的 20 条探测问题（知识库里没有）

只打向量通道（cosine + HNSW），不走 RRF 和精排——这里要的是「裸相似度」这个统计量本身。
"""
import json
import os
import statistics
import subprocess
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "scripts"))
from ragas_eval import load_env_file  # noqa: E402

load_env_file(ROOT / ".env")

base = os.environ.get("DASHSCOPE_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1")
key = os.environ.get("DASHSCOPE_API_KEY", "")
model = os.environ.get("DASHSCOPE_EMBEDDING_MODEL", "text-embedding-v3")

NEGATIVES = [
    "今天天气怎么样",
    "红烧肉怎么做才好吃",
    "世界杯冠军是哪支球队",
    "怎么减肥最快",
    "推荐一部好看的电影",
    "明天股市会涨吗",
    "如何种植番茄",
    "宝宝发烧怎么办",
    "驾照科目二怎么过",
    "北京到上海高铁多久",
    "英语四级怎么备考",
    "手机电池不耐用怎么办",
    "猫粮哪个牌子好",
    "怎么提高睡眠质量",
    "钢琴入门先学什么",
    "婚礼流程怎么安排",
    "感冒咳嗽吃什么药",
    "考研数学怎么复习",
    "汽车保养多久一次",
    "周末去哪里玩比较好",
]


def embed(texts):
    body = json.dumps({"model": model, "input": texts, "dimensions": 1024}).encode()
    req = urllib.request.Request(
        f"{base.rstrip('/')}/embeddings",
        data=body,
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {key}"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=60) as resp:
        data = json.loads(resp.read().decode())
    return [item["embedding"] for item in data["data"]]


def top1_sim(vec):
    lit = "[" + ",".join(f"{v:.8f}" for v in vec) + "]"
    sql = (
        "SELECT round((1 - (embedding <=> '" + lit + "'::vector))::numeric, 4) "
        "FROM chunks WHERE embedding IS NOT NULL "
        "ORDER BY embedding <=> '" + lit + "'::vector LIMIT 1;"
    )
    out = subprocess.run(
        ["docker", "exec", "rag-postgres", "psql", "-U", "postgres", "-d", "ragdb", "-t", "-A", "-c", sql],
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    return float(out.stdout.strip())


def collect(questions, tag):
    sims = []
    for i in range(0, len(questions), 10):
        batch = questions[i : i + 10]
        for q, v in zip(batch, embed(batch)):
            sims.append(top1_sim(v))
        print(f"  {tag} {min(i + 10, len(questions))}/{len(questions)}", flush=True)
    return sims


def describe(name, sims):
    s = sorted(sims)
    n = len(s)
    def pct(p):
        k = max(0, min(n - 1, int(round(p / 100 * (n - 1)))))
        return s[k]
    print(
        f"{name}: n={n} min={s[0]:.4f} p10={pct(10):.4f} p50={pct(50):.4f} "
        f"p90={pct(90):.4f} max={s[-1]:.4f} mean={statistics.mean(s):.4f}"
    )
    return {"n": n, "min": s[0], "p10": pct(10), "p50": pct(50), "p90": pct(90), "max": s[-1]}


questions = [l["question"] for l in (json.loads(x) for x in (ROOT / "data/eval/eval-20261117-120646.jsonl").read_text(encoding="utf-8").splitlines() if x.strip())]
print(f"相关侧 {len(questions)} 条 / 无关侧 {len(NEGATIVES)} 条")

rel = collect(questions, "相关")
neg = collect(NEGATIVES, "无关")

print()
describe("相关侧", rel)
describe("无关侧", neg)
