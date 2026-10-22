package com.ragagent.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import jakarta.annotation.Resource;

import java.util.List;
import java.util.Map;

@Repository 
public class ConversationRepository {
    private final JdbcTemplate jdbc;

    public ConversationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> findAllWithMessageCount() {
        // message_count 用子查询逐行统计，而不是 LEFT JOIN + GROUP BY：
        // 会话量本来就不大，子查询写起来更直观，也不容易漏掉 GROUP BY 字段。
        return jdbc.queryForList(
                "SELECT c.id, c.title, c.created_at, "
                        + "  (SELECT count(*) FROM messages m WHERE m.conversation_id = c.id) AS message_count "
                        + "FROM conversations c ORDER BY c.created_at DESC");
    }

    public List<Map<String, Object>> findMessagesByConversationId(String conversationId) {
        // 按 seq 升序返回该会话的全部消息，前端可直接按顺序渲染历史聊天记录。
        return jdbc.queryForList(
                "SELECT role, content, created_at FROM messages "
                        + "WHERE conversation_id = ? ORDER BY seq",
                conversationId);
    }

    public void deleteById(String conversationId) {
        // 只删 conversations 表那一行。messages 表里的消息不在本类职责内，
        // 由调用方（ConversationController）先调 chatMemory.clear() 触发删除。
        jdbc.update("DELETE FROM conversations WHERE id = ?", conversationId);
    }
}
