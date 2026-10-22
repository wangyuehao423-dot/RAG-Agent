package com.ragagent.controller;

import com.ragagent.repository.ConversationRepository;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;


@RestController 
@RequestMapping ("/api/conversations")
public class ConversationController {
    private final ChatMemoryProvider chatMemoryProvider;
    private final ConversationRepository conversationRepository;

    public ConversationController(ChatMemoryProvider chatMemoryProvider, ConversationRepository conversationRepository) {
        this.chatMemoryProvider = chatMemoryProvider;
        this.conversationRepository = conversationRepository;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return conversationRepository.findAllWithMessageCount();
    }

    @GetMapping("/{conversationId}/messages")
    public List<Map<String, Object>> messages(@PathVariable String conversationId) {
        return conversationRepository.findMessagesByConversationId(conversationId);
    }
    
    @DeleteMapping("/{conversationId}")
    public Map<String, Object> clear(@PathVariable String conversationId)
    {
        // 必须按 conversationId 取对应的窗口记忆来清：直接清默认单例（id="default"）
        // 会删掉一个从未写入过的空会话，目标会话的 messages 一行都删不掉。
        chatMemoryProvider.get(conversationId).clear();
        conversationRepository.deleteById(conversationId);
        return Map.of("cleared", conversationId);
    }
}
