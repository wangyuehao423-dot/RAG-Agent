package com.ragagent.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 【最小实现 · 先行依赖】——本版只为让 ReactAgent 能编译、能注入、能调用，
 * 只打印一行朴素日志，不做 JSON 结构化、不做截断处理。
 *
 * <p>完整实现（结构化 JSON、LinkedHashMap 保序、200 字截断）在「智能体 5」给出，
 * 届时用完整版整体覆盖本文件即可 —— 方法签名完全一致，
 * 所以 ReactAgent 一行都不用改。
 */
@Component
public class TraceLogger {

    private static final Logger log = LoggerFactory.getLogger(TraceLogger.class);

    public void record(String conversationId, String step, String input, String output,
                       int promptTokens, int completionTokens, long latencyMs) {
        log.info("[AGENT-TRACE] conversationId={} step={} promptTokens={} completionTokens={} latencyMs={}",
                conversationId, step, promptTokens, completionTokens, latencyMs);
    }
}
