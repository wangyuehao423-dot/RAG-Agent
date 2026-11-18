package com.ragagent.ingestion;

/**
 * 入库任务的消息载荷。
 *
 * <p>只带「重新执行一次入库」所需的最小上下文：消费者靠 documentId 回查 documents 行、
 * 靠 storedPath 读原始文件。不把文件字节塞进消息体——50MB 的文件会把 broker 内存打爆，
 * 而文件本来就已经落盘了，消息只需要指个路。
 *
 * @param documentId 文档 ID，消费者据此回查状态、写分块、做段级收敛
 * @param filename   原始文件名，决定解析器走 PDF 还是 docx
 * @param storedPath 落盘后的绝对路径，消费者从这里读原始字节
 * @param retryCount 已重试次数。跟着消息体走，重投时 +1，超过上限进死信
 */
public record IngestionTask(
        String documentId,
        String filename,
        String storedPath,
        int retryCount
) {
    /** 重试上限：3 次。再多基本可以断定不是抖动，而是数据或依赖坏了 */
    public static final int MAX_RETRY = 3;

    public IngestionTask nextAttempt() {
        return new IngestionTask(documentId, filename, storedPath, retryCount + 1);
    }
}
