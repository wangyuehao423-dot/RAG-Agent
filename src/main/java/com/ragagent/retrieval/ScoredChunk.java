package com.ragagent.retrieval;

/**
 * 单通道检索命中（向量通道或词法通道的原始结果）。
 *
 * @param id         分块 ID
 * @param content    分块内容
 * @param sourceName 来源文档名
 * @param score      通道打分（向量通道为余弦相似度，词法通道为 ts_rank）
 */
public record ScoredChunk(String id, String content, String sourceName, double score) {
}
