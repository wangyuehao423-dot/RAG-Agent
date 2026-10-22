package com.ragagent.dto;

import java.util.List;

/**
 * 流式问答的「结束后才确定」的结果载体。
 *
 * <p>流式响应有天然的时序矛盾：{@code sources} / {@code refused} / {@code token 用量}
 * 只有在模型**输出完毕后**才能最终确定（模型可能主动拒答、usage 只在最后一个分片返回），
 * 但 SSE 的结束事件又必须在流结束时刻发出。
 *
 * <p>因此用一个可变载体承载这些字段：Agent 在流完成回调里写入最终值，
 * Controller 在同一个完成回调里读取并下发。字段用 {@code volatile} 保证可见性
 * （Reactor 回调可能在其它线程执行）。
 */
public class StreamOutcome {

    private volatile List<Source> sources = List.of();
    private volatile boolean refused;
    private volatile int promptTokens;
    private volatile int completionTokens;

    public List<Source> getSources() {
        return sources;
    }

    public void setSources(List<Source> sources) {
        this.sources = sources == null ? List.of() : sources;
    }

    public boolean isRefused() {
        return refused;
    }

    public void setRefused(boolean refused) {
        this.refused = refused;
    }

    public int getPromptTokens() {
        return promptTokens;
    }

    public int getCompletionTokens() {
        return completionTokens;
    }

    public void setUsage(int promptTokens, int completionTokens) {
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
    }
}
