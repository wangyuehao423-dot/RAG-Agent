package com.ragagent.dto;

/**
 * 对话请求体。
 *
 * @param question       用户问题
 * @param conversationId 会话 ID（同一会话传相同 ID 以保持多轮记忆；不传则由后端生成）
 */
public record ChatRequest(String question, String conversationId) {
}
