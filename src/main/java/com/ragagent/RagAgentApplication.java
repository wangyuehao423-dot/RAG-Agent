package com.ragagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 企业知识库智能问答 Agent 启动入口。
 *
 * <p>核心能力：
 * <ul>
 *   <li>文档解析入库（txt / md / pdf / docx）→ 语义分块 → 向量化 → 入库</li>
 *   <li>混合检索：向量(HNSW 余弦) + 词法(中文 bigram 全文检索) → RRF 融合</li>
 *   <li>Agent 内核：ReAct 工具调用（检索工具）+ 引用溯源 + 低置信度拒答</li>
 *   <li>多轮记忆、流式 SSE 输出、token 统计、评测</li>
 * </ul>
 */
@SpringBootApplication
public class RagAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagAgentApplication.class, args);
    }
}