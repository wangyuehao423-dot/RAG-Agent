#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
RAGAS 离线评测：读 Java 导出的四列 JSONL，算四个指标，把结论回写到 eval_runs。

===============================================================================
为什么算分放在 Python、出数据放在 Java
===============================================================================
RAGAS 的指标实现里有一堆非平凡细节：faithfulness 要先拆 claim 再逐条做 NLI 判定，
answer_relevancy 要用 embedding 反推「答案是否在回答问题」，context_precision 要判断
「这一块到底有没有用于作答」。在 Java 里重写一遍，等于自己给自己发合格证——
面试官问「你怎么保证你的实现和别人一致」，答不出来。

所以边界很清楚：
  Java 出数据 —— 它最懂自己的检索与生成链路，能拿到原始片段和真实答案
  Python 算分 —— 用现成框架，指标实现不自己造
  Java 读结论 —— 只负责展示和跟基线 diff

===============================================================================
四个指标各测什么
===============================================================================
  faithfulness        答案里的事实主张，有多少能在检索到的资料里找到  （评生成，防编造）
  answer_relevancy    答案有没有正面回答问题                        （评生成，防答非所问）
  context_precision   检索回来的 5 块里，有多少块真的有用             （评检索，防噪音）
  context_recall      该找到的资料，找到了多少                       （评检索，防漏召回）

前两个指回「生成」，后两个指回「检索」。分两对的意义是能定位问题：
忠实度低但召回率高 = 模型在编；召回率低 = 检索没捞到，换模型没用。

===============================================================================
用法
===============================================================================
  python scripts/ragas_eval.py --input data/eval/eval-20261111-201500.jsonl
  python scripts/ragas_eval.py --input xxx.jsonl --limit 30      # 先抽 30 条试跑
  python scripts/ragas_eval.py --input xxx.jsonl --no-writeback  # 只算不回写
"""
import argparse
import json
import os
import random
import sys
from pathlib import Path


def load_env_file(path: Path) -> None:
    """把项目根目录的 .env 读进环境变量（和 run.cmd 的行为保持一致）。"""
    if not path.exists():
        return
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        # 不覆盖已经存在的环境变量：命令行显式传入的优先级更高
        os.environ.setdefault(key.strip(), value.strip())


def load_rows(path: Path, limit: int | None, sample: int | None = None, seed: int = 42) -> list[dict]:
    rows = []
    skipped: list[str] = []  # 本轮被剔除的题（空答案/空上下文），最后汇总打印
    with path.open(encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            row = json.loads(line)
            # 空答案 / 空上下文的行必须剔掉，不能带进评测。
            # 原因有两个，都会让整轮评测白跑：
            #   1) faithfulness 要把答案拆成 claim 再逐条做 NLI，答案为空时拆不出 claim，
            #      RAGAS 会把空串直接发给 embedding 接口，百炼对空 contents 返回 400；
            #   2) 空上下文的行 context_precision 必然是 0，会把均值往下拽，
            #      但它反映的是「检索没召回」，不是「答案质量差」，混在一起两个指标都失准。
            answer = (row.get("answer") or "").strip()
            contexts = [c for c in (row.get("contexts") or []) if (c or "").strip()]
            if not answer or not contexts:
                skipped.append(row.get("question", ""))
                continue
            # RAGAS 0.4 的列名是 SingleTurnSample 的字段名，和 Java 导出的四列不同名，
            # 在这里一次性改名，下游只认一套列名。
            rows.append(
                {
                    "user_input": row.get("question", ""),
                    "response": answer,
                    "retrieved_contexts": contexts,
                    "reference": (row.get("ground_truth") or "").strip(),
                }
            )
            if limit and len(rows) >= limit:
                break
    if skipped:
        print(f"[WARN] 跳过 {len(skipped)} 条空答案/空上下文用例（不参与打分）")
        for q in skipped[:5]:
            print(f"       - {q[:50]}")
    # 抽样而不是取前 N 条：JSONL 是按用例表顺序导出的，
    # 前 N 条会全部落在同一个项目的题上，某一片语料答得差就被放大成整体差。
    # 固定 seed 是为了「同一批数据重跑得到同一批样本」，否则两次评测的数字没法比。
    if sample and sample < len(rows):
        picked = sorted(random.Random(seed).sample(range(len(rows)), sample))
        rows = [rows[i] for i in picked]
    return rows


def require(module: str, hint: str):
    try:
        return __import__(module)
    except ImportError:
        sys.exit(f"[ERROR] 缺少依赖 {module}。安装：{hint}")


def main() -> None:
    parser = argparse.ArgumentParser(description="RAGAS 四指标评测")
    parser.add_argument("--input", required=True, help="Java 导出的 JSONL 文件")
    parser.add_argument("--api", default="http://localhost:8080", help="后端地址，用于回写结果")
    parser.add_argument("--limit", type=int, default=None, help="只跑前 N 条（要快就缩小样本量，不降低判据质量）")
    parser.add_argument("--sample", type=int, default=None, help="随机抽 N 条（固定 seed，可复现）")
    parser.add_argument("--seed", type=int, default=42, help="抽样用的随机种子")
    parser.add_argument("--retry-failed", action="store_true",
                        help="只补算缓存里失败（null）的行，已有结果不动")
    parser.add_argument("--workers", type=int, default=2, help="并发数，网关扛不住就调小")
    parser.add_argument("--no-writeback", action="store_true", help="只算分，不回写数据库")
    # 单指标运行：四个指标互不依赖，拆成四个进程并行跑，等于把并发翻四倍；
    # 中途被杀也只损失一个指标，不用整轮重来。
    parser.add_argument("--metrics", default="faithfulness,answer_relevancy,context_precision,context_recall",
                        help="要算的指标，逗号分隔；只跑一个就写一个名字")
    args = parser.parse_args()

    root = Path(__file__).resolve().parent.parent
    load_env_file(root / ".env")

    input_path = Path(args.input)
    if not input_path.is_absolute():
        input_path = root / input_path
    if not input_path.exists():
        sys.exit(f"[ERROR] 找不到输入文件：{input_path}")

    rows = load_rows(input_path, args.limit, args.sample, args.seed)
    if not rows:
        sys.exit("[ERROR] JSONL 是空的。先调 POST /api/eval/export 生成数据。")
    print(f"[INFO] 读到 {len(rows)} 条用例：{input_path.name}")

    require("datasets", "pip install datasets")
    require("ragas", "pip install ragas")
    require("langchain_openai", "pip install langchain-openai")

    import ragas
    from datasets import Dataset
    from ragas import RunConfig, evaluate
    from ragas.embeddings import LangchainEmbeddingsWrapper
    from ragas.llms import LangchainLLMWrapper
    from langchain_openai import ChatOpenAI, OpenAIEmbeddings

    # RAGAS 0.4 把旧指标收进了私有模块：evaluate() 只认这些 Metric 实例；
    # 新式 collections 指标要求 InstructorLLM，接不了 OpenAI 兼容网关。
    from ragas.metrics._answer_relevance import answer_relevancy
    from ragas.metrics._context_precision import context_precision
    from ragas.metrics._context_recall import context_recall
    from ragas.metrics._faithfulness import faithfulness

    print(f"[INFO] ragas {ragas.__version__}")

    chat_base = os.environ.get("DEEPSEEK_BASE_URL", "https://tokenx24.com/v1")
    chat_key = os.environ.get("DEEPSEEK_API_KEY", "")
    # 默认值和 application.yml 保持一致：线上是哪个模型，裁判就用哪个，
    # 「评测口径 = 线上口径」这句话才成立。deepseek 在密集请求下会撞读超时。
    chat_model = os.environ.get("DEEPSEEK_MODEL", "glm-5.3-flash")

    embed_base = os.environ.get("DASHSCOPE_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1")
    embed_key = os.environ.get("DASHSCOPE_API_KEY", "")
    embed_model = os.environ.get("DASHSCOPE_EMBEDDING_MODEL", "text-embedding-v3")

    if not chat_key or not embed_key:
        sys.exit("[ERROR] .env 里缺 DEEPSEEK_API_KEY / DASHSCOPE_API_KEY")

    # 裁判模型就是线上那一个：不用另配 key，也保证「评测口径 = 线上口径」。
    # temperature=0 是关键：judge 有随机性的话，同一条用例跑两次得分不同，
    # 前后对比就变成了比随机噪声。
    evaluator_llm = LangchainLLMWrapper(
        ChatOpenAI(model=chat_model, api_key=chat_key, base_url=chat_base, temperature=0)
    )
    evaluator_embeddings = LangchainEmbeddingsWrapper(
        # chunk_size=10：和 Java 侧 max-segments-per-batch 同一个原因——
        # 百炼 text-embedding-v3 单次最多 10 条，超过直接 400。
        #
        # check_embedding_ctx_length=False 是必须的，不是可选优化：
        # LangChain 的 OpenAIEmbeddings 默认会在本地用 tiktoken 把文本切成 token id 再发，
        # 发出去的是 input=[[1234, 5678, ...]]（整数数组）。OpenAI 官方接受这种格式，
        # 但百炼的 OpenAI 兼容层只认字符串，会直接回 400
        # 「contents is neither str nor list of str」。表现就是 answer_relevancy
        # 整列算不出来（它靠 embedding 算相似度），而其他三个指标准好不受影响。
        OpenAIEmbeddings(
            model=embed_model,
            api_key=embed_key,
            base_url=embed_base,
            chunk_size=10,
            check_embedding_ctx_length=False,
        )
    )

    print(f"[INFO] 裁判模型 = {chat_model}，并发 = {args.workers}")

    dataset = Dataset.from_list(rows)

    metric_map = {
        "faithfulness": faithfulness,
        "answer_relevancy": answer_relevancy,
        "context_precision": context_precision,
        "context_recall": context_recall,
    }
    wanted = [m.strip() for m in args.metrics.split(",") if m.strip()]
    unknown = [m for m in wanted if m not in metric_map]
    if unknown:
        sys.exit(f"[ERROR] 不认识的指标：{unknown}")

    # 断点续跑：每个指标算完就单独落盘。
    # 一个指标 = 185 条 × 数分钟，整轮重来的代价太高；杀进程只该损失正在算的那一个。
    cache_dir = root / "data" / "eval" / ".cache"
    cache_dir.mkdir(parents=True, exist_ok=True)

    def cache_path(name: str) -> Path:
        # 文件名带样本量：60 条的缓存不能被 185 条的运行当成「已算完」而跳过，
        # 反过来也一样——两个样本量的均值不可比。
        return cache_dir / f"{input_path.stem}-n{len(rows)}-{name}.json"

    def load_cache(name: str) -> list:
        p = cache_path(name)
        if not p.exists():
            return []
        try:
            return json.loads(p.read_text(encoding="utf-8"))
        except Exception:
            return []

    scores: dict[str, float | None] = {}
    valid_counts: dict[str, int] = {}

    for name in wanted:
        cached = load_cache(name)
        target_idx: list[int]
        sub_rows: list[dict]

        if len(cached) == len(rows):
            pending = [i for i, v in enumerate(cached) if v is None]
            valid = [v for v in cached if v is not None]
            if not pending or not args.retry_failed:
                scores[name] = float(sum(valid) / len(valid)) if valid else None
                valid_counts[name] = len(valid)
                print(f"[SKIP] {name} 已有缓存（有效 {len(valid)}/{len(rows)}），直接用")
                continue
            # 补算模式：只重跑那些 null 的行。
            # 高并发下网关会拒掉一部分请求，把它们整轮重来的代价太高；
            # 而且均值必须建立在足量样本上，16/60 的均值等于拿四分之一的数据说事。
            print(f"[INFO] 补算 {name}：重跑 {len(pending)} 条失败行（已有 {len(valid)} 条有效）")
            target_idx = pending
            sub_rows = [rows[i] for i in pending]
        else:
            print(f"[INFO] 开始计算 {name}（{len(rows)} 条）...")
            target_idx = list(range(len(rows)))
            sub_rows = rows
            cached = [None] * len(rows)

        result = evaluate(
            Dataset.from_list(sub_rows),
            metrics=[metric_map[name]],
            llm=evaluator_llm,
            embeddings=evaluator_embeddings,
            # raise_exceptions=False：单条用例失败（裁判模型返回格式坏了、上游偶发 502）
            # 只让那一行变 NaN，不中断整轮。一条坏就整体崩掉，等于每次都要赌上游完全不出错。
            raise_exceptions=False,
            # max_retries / max_wait：网关是共享的，502 是常态而不是意外。
            # 不重试的话一轮跑下来一半行是 NaN，均值变成「剩下那几行」的均值，
            # 比跑不出来更危险——数字看着有，其实样本已经不是原来那批了。
            run_config=RunConfig(
                timeout=180,
                max_retries=12,
                max_wait=30,
                max_workers=max(1, args.workers),
            ),
        )

        df = result.to_pandas()
        if name not in df.columns:
            print(f"[WARN] {name} 没算出任何结果")
            if not any(v is not None for v in cached):
                scores[name] = None
                valid_counts[name] = 0
            continue

        vals = df[name].tolist()
        # NaN 落成 null：JSON 不认 NaN，写进去下次就读不出来了
        vals = [None if (isinstance(v, float) and v != v) else v for v in vals]
        if len(vals) != len(target_idx):
            print(f"[WARN] {name} 返回行数 {len(vals)} != 请求行数 {len(target_idx)}，按位置对齐，尾部丢弃")
        for k, i in enumerate(target_idx):
            if k < len(vals):
                cached[i] = vals[k]

        cache_path(name).write_text(json.dumps(cached, ensure_ascii=False), encoding="utf-8")

        valid = [v for v in cached if v is not None]
        scores[name] = float(sum(valid) / len(valid)) if valid else None
        valid_counts[name] = len(valid)
        print(f"[OK] {name} = {scores[name]}（有效 {len(valid)}/{len(rows)}），已落盘")

    print(f"[INFO] 有效样本数（去掉计算失败行）：{valid_counts}")
    for name, cnt in valid_counts.items():
        if cnt < len(rows) * 0.9:
            print(f"[WARN] {name} 只有 {cnt}/{len(rows)} 条算成功，均值代表性不足，建议调小 --workers 重跑")

    faithfulness_v = _num(scores.get("faithfulness"))
    relevancy_v = _num(scores.get("answer_relevancy"))
    precision_v = _num(scores.get("context_precision"))
    recall_v = _num(scores.get("context_recall"))

    print("\n================ RAGAS 四指标 ================")
    print(f"  忠实度   faithfulness      {_fmt(faithfulness_v)}   （答案的事实有多少能在资料里找到）")
    print(f"  答案相关性 answer_relevancy {_fmt(relevancy_v)}   （有没有正面回答）")
    print(f"  上下文精确 context_precision {_fmt(precision_v)}   （top-5 里有多少块真有用）")
    print(f"  上下文召回 context_recall    {_fmt(recall_v)}   （该找到的找到了多少）")
    print("=============================================\n")

    print("[提醒] 这四个数不能只看绝对值，要跟基线比。第一次跑只是建立基线。")
    print("[提醒] 30 条人工抽检是验证「裁判本身准不准」，和这个脚本是两件事——")
    print("       LLM 当裁判有长度/自我/位置/宽松四类偏差，不校准就不该直接采信它的分数。")

    if args.no_writeback:
        print("[INFO] --no-writeback，跳过回写。")
        return

    # 唯一键：四个指标都齐了才有资格落库。
    # 单指标进程（--metrics faithfulness）只负责算，回写交给最后汇总的那一次，
    # 否则四个进程各自 POST 一次，会把平均值这一行写成半张表。
    missing = [n for n in ("faithfulness", "answer_relevancy", "context_precision", "context_recall")
               if scores.get(n) is None]
    if missing:
        print(f"[INFO] 还缺 {missing}，本次不回写（等四个指标都算完再汇总回写）。")
        return

    import urllib.request

    payload = json.dumps({
        "caseCount": len(rows),
        "faithfulness": faithfulness_v,
        "answerRelevancy": relevancy_v,
        "contextPrecision": precision_v,
        "contextRecall": recall_v,
        "note": f"input={input_path.name}" + (f" limit={args.limit}" if args.limit else ""),
    }).encode("utf-8")

    request = urllib.request.Request(
        f"{args.api.rstrip('/')}/api/eval/report",
        data=payload,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=15) as resp:
            print(f"[INFO] 已回写：{resp.read().decode('utf-8')}")
    except Exception as e:  # noqa: BLE001
        print(f"[WARN] 回写失败（后端没起？）：{e}")
        print("       指标已经算出来了，可以手工 POST 到 /api/eval/report。")


def _num(value):
    if value is None:
        return None
    try:
        return round(float(value), 4)
    except (TypeError, ValueError):
        return None


def _fmt(value):
    return "  NaN " if value is None else f"{value:.4f}"


if __name__ == "__main__":
    main()
