"""找出导出时被拒答的 2 条题，并核对 eval_runs 表里的历史记录。"""
import json
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
exported = {
    json.loads(l)["question"]
    for l in (ROOT / "data/eval/eval-20261117-120646.jsonl").read_text(encoding="utf-8").splitlines()
    if l.strip()
}

sql = "SELECT id, question FROM eval_cases ORDER BY id;"
out = subprocess.run(
    ["docker", "exec", "rag-postgres", "psql", "-U", "postgres", "-d", "ragdb", "-t", "-A", "-F", "\t", "-c", sql],
    capture_output=True,
    text=True,
    encoding="utf-8",
)
all_cases = [line.split("\t", 1) for line in out.stdout.strip().splitlines()]

missing = [(cid, q) for cid, q in all_cases if q not in exported]
print(f"全部 {len(all_cases)} 条，导出 {len(exported)} 条，缺 {len(missing)} 条：")
for cid, q in missing:
    print("  ", cid, q)

print("\n--- eval_runs ---")
out = subprocess.run(
    [
        "docker", "exec", "rag-postgres", "psql", "-U", "postgres", "-d", "ragdb",
        "-c", "SELECT id, case_count, faithfulness, answer_relevancy, context_precision, context_recall, note, created_at FROM eval_runs ORDER BY id DESC LIMIT 5;",
    ],
    capture_output=True,
    text=True,
    encoding="utf-8",
)
print(out.stdout)
