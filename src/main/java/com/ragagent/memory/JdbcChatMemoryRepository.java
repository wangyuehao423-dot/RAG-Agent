package com.ragagent.memory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;


@Repository
public class JdbcChatMemoryRepository implements ChatMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcChatMemoryRepository.class);

    private final JdbcTemplate jdbc;

    public List<String> findConversationIds() {
        return jdbc.queryForList("SELECT DISTINCT conversation_id FROM messages", String.class);
    }

    public JdbcChatMemoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String conversationId = memoryId.toString();
        return jdbc.query(
                "SELECT role, content FROM messages WHERE conversation_id = ? ORDER BY seq",
                (rs, rowNum) -> toMessage(rs.getString("role"), rs.getString("content")),
                conversationId);
    }

    @Override
    @Transactional
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String conversationId = memoryId.toString();
        // 整体替换语义：先清空该会话历史，再写入「窗口截断后」的完整消息列表
        jdbc.update("DELETE FROM messages WHERE conversation_id = ?", conversationId);
        if (messages.isEmpty()) {
            return;
        }
        ensureConversation(conversationId, messages);

        List<Object[]> batch = messages.stream()
                .map(m -> new Object[]{conversationId, roleOf(m), textOf(m)})
                .toList();
        jdbc.batchUpdate(
                "INSERT INTO messages(conversation_id, role, content) VALUES (?, ?, ?)", batch);
    }
    @Override
    public void deleteMessages(Object memoryId) {
        jdbc.update("DELETE FROM messages WHERE conversation_id = ?", memoryId.toString());
    }

    private void ensureConversation(String conversationId, List<ChatMessage> messages) {
        String title = messages.stream()
                .filter(m -> m.type() == ChatMessageType.USER)
                .map(JdbcChatMemoryRepository::textOf)
                .findFirst()
                .map(t -> t.length() > 50 ? t.substring(0, 50) : t)
                .orElse("新会话");
        jdbc.update("INSERT INTO conversations(id, title) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                conversationId, title);
    }
    private static String roleOf(ChatMessage m) {
        return m.type().name().toLowerCase();
    }

    private static String textOf(ChatMessage m) {
        if (m instanceof AiMessage ai) {
            return ai.text();
        }
        if (m instanceof UserMessage user) {
            return user.singleText();
        }
        if (m instanceof SystemMessage system) {
            return system.text();
        }
        return "";
    }

    private ChatMessage toMessage(String role, String content) {
        String text = content == null ? "" : content;
        return switch (role) {
            case "user" -> UserMessage.from(text);
            case "ai", "assistant" -> AiMessage.from(text);
            case "system" -> SystemMessage.from(text);
            default -> {
                log.error("暂不支持持久化 {} 类型消息，conversationId 上下文可能不完整", role);
                throw new IllegalStateException("暂不支持持久化的消息类型: " + role);
            }
        };
    }

}
