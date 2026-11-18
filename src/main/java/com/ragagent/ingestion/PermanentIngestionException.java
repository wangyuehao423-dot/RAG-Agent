package com.ragagent.ingestion;

/**
 * 不可重试的入库失败：文件损坏、编码解析失败、内容为空。
 *
 * <p>为什么要把「不可重试」单独标出来：可重试失败（embedding 抖动、超时）应该重投，
 * 不可重试失败如果也重投，只会在队列里死循环——文件已经坏了，重投一百次还是坏。
 * 所以这两类的处理必须分开：可重试 → 延迟队列；不可重试 → 置 FAILED + ack（不重投）。
 */
public class PermanentIngestionException extends RuntimeException {

    public PermanentIngestionException(String message) {
        super(message);
    }

    public PermanentIngestionException(String message, Throwable cause) {
        super(message, cause);
    }
}
