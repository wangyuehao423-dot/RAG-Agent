package com.ragagent.config;

import com.ragagent.memory.JdbcChatMemoryRepository;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 对话记忆配置：使用窗口记忆（MessageWindowChatMemory）+ PostgreSQL 持久化仓储。
 *
 * <p>当历史消息超过 maxMessages 条时，自动淘汰最旧的消息，避免上下文窗口无限膨胀。
 *
 * <p>相比 LangChain4j 默认的内存仓储（重启即丢），这里注入 {@link JdbcChatMemoryRepository}，
 * 让多轮记忆落库——服务重启后同一 conversationId 仍能接上上下文。
 *
 * <p>说明：这是「短期记忆」的持久化。生产环境的「长期记忆」可在此基础上扩展为
 * 向量化记忆（跨会话召回）+ 摘要压缩，属于可选的增强项。
 */
@Configuration
public class ChatMemoryConfig {

    @Bean
    public ChatMemory chatMemory(JdbcChatMemoryRepository repository,
                                 @Value("${app.memory.max-messages:12}") int maxMessages) {
        return MessageWindowChatMemory.builder()
                .chatMemoryStore(repository)
                .maxMessages(maxMessages)
                .build();
    }

    /**
     * 按 conversationId 提供窗口记忆实例：Agent 每次问答都要按会话取一份独立记忆，
     * 而不是共用一个 ChatMemory 单例（单例会让所有会话上下文互相串味）。
     */
    @Bean
    public ChatMemoryProvider chatMemoryProvider(JdbcChatMemoryRepository repository,
                                                 @Value("${app.memory.max-messages:12}") int maxMessages) {
        return memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .chatMemoryStore(repository)
                .maxMessages(maxMessages)
                .build();
    }
}