package com.ragagent.retrieval;

/**
 * 混合检索最终结果（RRF 融合后）。
 *
 * @param id           分块 ID
 * @param content      分块内容
 * @param sourceName   来源文档名
 * @param similarity   向量通道余弦相似度（未进向量候选集时为 0）
 * @param lexicalScore 词法通道 ts_rank 得分（未被词法通道命中时为 0）
 * @param rrfScore     融合后的 RRF 得分
 */
public record RetrievalResult(String id, String content, String sourceName,
                              double similarity, double lexicalScore, double rrfScore) {
}
