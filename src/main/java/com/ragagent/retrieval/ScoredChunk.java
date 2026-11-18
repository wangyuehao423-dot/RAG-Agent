package com.ragagent.retrieval;

/**
 * 单通道检索命中（向量通道或词法通道的原始结果）。
 *
 * <p>除了内容与分数，还带着「这块内容在原文件里的位置」四个字段。
 * 它们对排序毫无作用，唯一用途是把引用来源做成可点击的：前端拿 documentId 取原文，
 * 再用 startLine / endLine 把视图滚到那一块。
 *
 * @param id         分块 ID
 * @param content    分块内容
 * @param sourceName 来源文档名
 * @param score      通道打分（向量通道为余弦相似度，词法通道为 ts_rank）
 * @param documentId 所属文档 ID
 * @param chunkIndex 块在文档内的序号（0 基）
 * @param startLine  该块正文在原文中的起始行号（1 基）；老数据为 0
 * @param endLine    该块正文在原文中的结束行号（1 基、闭区间）；老数据为 0
 */
public record ScoredChunk(String id, String content, String sourceName, double score,
                          String documentId, int chunkIndex, int startLine, int endLine) {
}
