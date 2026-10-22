package com.ragagent.dto;

import java.util.List;

/**
 * 同步问答结果。
 *
 * @param answer           最终回答文本
 * @param sources          引用来源列表
 * @param promptTokens     输入 token 数
 * @param completionTokens 输出 token 数
 * @param refused          是否因低置信度触发拒答
 */
public record AgentAnswer(String answer, List<Source> sources,
                          int promptTokens, int completionTokens, boolean refused) {
}
