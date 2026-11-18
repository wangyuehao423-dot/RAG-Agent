package com.ragagent.dto;

/**
 * 引用来源（一条检索到的知识库片段）。
 *
 * <p>后四个字段是「可回溯」用的定位锚点：前端拿 documentId 调
 * {@code GET /api/documents/{id}/raw} 取回原文，再用 startLine / endLine
 * 把原文视图滚到那一块并高亮。没有它们，来源就只能显示一个名字，用户无法核实。
 *
 * @param sourceName   来源文档名
 * @param similarity   向量通道的余弦相似度（用于置信度展示）
 * @param lexicalScore 词法通道（ts_rank）的命中强度；该片段未被词法通道召回时为 0
 * @param snippet      片段摘要
 * @param documentId   所属文档 ID；为空表示无法跳转
 * @param chunkIndex   块在文档内的序号（0 基）
 * @param startLine    该块正文在原文中的起始行号（1 基）；为 0 表示入库时没记（老数据）
 * @param endLine      该块正文在原文中的结束行号（1 基、闭区间）；为 0 表示老数据
 */
public record Source(String sourceName, double similarity, double lexicalScore, String snippet,
                     String documentId, int chunkIndex, int startLine, int endLine) {
}
