package com.ragagent.dto;

/**
 * 引用来源（一条检索到的知识库片段）。
 *
 * @param sourceName   来源文档名
 * @param similarity   向量通道的余弦相似度（用于置信度展示）
 * @param lexicalScore 词法通道（ts_rank）的命中强度；该片段未被词法通道召回时为 0
 * @param snippet      片段摘要
 */
public record Source(String sourceName, double similarity, double lexicalScore, String snippet) {
}
