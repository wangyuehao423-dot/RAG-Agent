#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""诊断单条用例的 faithfulness 拆句与逐句判定（定位「答案对但忠实度低」的真正原因）。"""
import asyncio
import json
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def load_env(path: Path) -> None:
    if not path.exists():
        return
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        os.environ.setdefault(k.strip(), v.strip())


load_env(ROOT / ".env")

from langchain_openai import ChatOpenAI
from ragas.llms import LangchainLLMWrapper
from ragas.metrics._faithfulness import (
    Faithfulness,
    NLIStatementInput,
    StatementGeneratorInput,
)

llm = LangchainLLMWrapper(
    ChatOpenAI(
        model=os.environ.get("DEEPSEEK_MODEL", "glm-5.3-flash"),
        api_key=os.environ.get("DEEPSEEK_API_KEY", ""),
        base_url=os.environ.get("DEEPSEEK_BASE_URL", "https://tokenx24.com/v1"),
        temperature=0,
    )
)


async def main() -> None:
    src = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "data/eval/eval-20261117-120646.jsonl"
    target = sys.argv[2] if len(sys.argv) > 2 else "向量通道一次召回多少条"
    row = None
    for line in src.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        r = json.loads(line)
        if target in (r.get("question") or ""):
            row = r
            break
    if row is None:
        sys.exit(f"未找到包含「{target}」的用例")

    m = Faithfulness()
    m.llm = llm
    d = {"user_input": row["question"], "response": row["answer"],
         "retrieved_contexts": row["contexts"]}

    print("问题:", d["user_input"])
    print("答案:", d["response"])
    print("片段数:", len(d["retrieved_contexts"]))

    print("\n[1/2] 拆句 …")
    st = await m.statement_generator_prompt.generate(
        data=StatementGeneratorInput(question=d["user_input"], answer=d["response"]), llm=llm
    )
    for i, s in enumerate(st.statements, 1):
        print(f"  S{i}: {s}")

    print("\n[2/2] 逐句判是否被片段直接支撑 …")
    v = await m.nli_statements_prompt.generate(
        data=NLIStatementInput(context="\n".join(d["retrieved_contexts"]), statements=st.statements),
        llm=llm,
    )
    for i, s in enumerate(v.statements, 1):
        flag = "支持 " if s.verdict else "不支持"
        print(f"  [{flag}] {s.statement}")
        print(f"           理由: {s.reason}")
    ok = sum(1 for s in v.statements if s.verdict)
    print(f"\n忠实度 = {ok}/{len(v.statements)} = {ok / max(len(v.statements), 1):.3f}")


asyncio.run(main())
