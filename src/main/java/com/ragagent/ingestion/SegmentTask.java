package com.ragagent.ingestion;

import java.util.List;

/**
 * 段级入库任务的消息载荷：一段一条消息。
 *
 * <p>为什么把段内文本直接放进消息体，而不是让消费者重新读文件、重新解析：
 * 重新解析意味着每一段都要把整篇文档再解析一遍（一份 100MB 的 PDF 解析一次几十秒），
 * 段数越多浪费越大。段文本是解析的产物，一次解析、随消息分发，重投时也不用再解析。
 *
 * <p>为什么不整篇一条消息：整篇一条会跑十几分钟，prefetch 一占其他文档全排队；
 * 而且 MQ 重投等于整篇重来，分段就白做了。
 *
 * @param documentId 文档 ID，段收敛靠它回写 documents
 * @param filename   原始文件名，写 chunks.source_name 用
 * @param seq        段序号（从 0 开始），唯一键 (document_id, seq) 的后半段
 * @param chunkStart 本段第一块在全文中的下标，保证 chunk_index 全局连续
 * @param chunks     本段的分块文本（已带标题路径前缀）
 * @param lineSpans  与 {@code chunks} 同序的行号范围，写 chunks.start_line / end_line 用；
 *                   历史消息没有这个字段，反序列化后为 null，紧凑构造器统一成空列表
 * @param retryCount 已重试次数
 */
public record SegmentTask(
        String documentId,
        String filename,
        int seq,
        int chunkStart,
        List<String> chunks,
        List<LineSpan> lineSpans,
        int retryCount
) {
    public static final int MAX_RETRY = 3;

    /** 一块正文在原文里的行号范围（1 基、闭区间） */
    public record LineSpan(int startLine, int endLine) {
    }

    /** 兼容没有 lineSpans 字段的历史消息 */
    public SegmentTask {
        if (lineSpans == null) {
            lineSpans = List.of();
        }
    }

    public SegmentTask nextAttempt() {
        return new SegmentTask(documentId, filename, seq, chunkStart, chunks, lineSpans, retryCount + 1);
    }
}
