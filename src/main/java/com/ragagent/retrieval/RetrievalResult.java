package com.ragagent.retrieval;

/**
 * 混合检索的结果。四个分数各回答一个不同的问题，不能合并：
 *
 * @param id           分块 ID
 * @param content      分块正文（送去精排、拼进 prompt 的都是它）
 * @param sourceName   来源文档名（回答里的引用标注用它）
 * @param similarity   向量通道余弦相似度（未进向量候选集时为 0）——「语义上像不像」
 * @param lexicalScore 词法通道 ts_rank 得分（未被词法通道命中时为 0）——「字面上提没提到」
 * @param rrfScore     两路排名倒数之和——「两路是不是都把它排在前面」，只是排序依据
 * @param rerankScore  交叉编码器给的相关性分——「看过两者交互后到底多相关」；
 *                     精排失败降级、或候选数不足没跑精排时为 0
 * @param documentId   所属文档 ID——引用来源跳转原文时用它去取全文
 * @param chunkIndex   块在文档内的序号（0 基）
 * @param startLine    该块正文在原文中的起始行号（1 基）；老数据为 0
 * @param endLine      该块正文在原文中的结束行号（1 基、闭区间）；老数据为 0
 *
 * <p>为什么四个都要留着：{@code similarity} 和 {@code lexicalScore} 是拒答判据的输入
 * （它们是「绝对强度」，且各自的背景分布能标定出分位阈值）；
 * {@code rrfScore} 只在融合那一步有用，它跟强度无关——第 1 名永远是 1/(60+1)，
 * 不管这个第 1 名是 0.9 还是 0.31，所以绝不能拿它判断「值不值得答」。
 *
 * <p>后四个字段（documentId / chunkIndex / startLine / endLine）不参与任何打分与排序，
 * 纯粹是「可回溯」需要的定位信息：没有它们，前端只能显示一个文档名，点不动。
 */
public record RetrievalResult(String id, String content, String sourceName,
                              double similarity, double lexicalScore, double rrfScore,
                              double rerankScore,
                              String documentId, int chunkIndex, int startLine, int endLine) {

    /** 融合后、精排前的形态：精排分留 0，跑完精排再补。 */
    public RetrievalResult(String id, String content, String sourceName,
                           double similarity, double lexicalScore, double rrfScore,
                           String documentId, int chunkIndex, int startLine, int endLine) {
        this(id, content, sourceName, similarity, lexicalScore, rrfScore, 0.0,
                documentId, chunkIndex, startLine, endLine);
    }

    /** 精排后补上分数，其余字段不变。 */
    public RetrievalResult withRerankScore(double score) {
        return new RetrievalResult(id, content, sourceName, similarity, lexicalScore, rrfScore, score,
                documentId, chunkIndex, startLine, endLine);
    }
}
