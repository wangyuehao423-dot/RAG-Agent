package com.ragagent.retrieval;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class QueryRewriter {
    private static final String SYSTEM_PROMPT = """
            你是检索问句改写器。用户会给你一段对话历史和本轮问题。
            请把本轮问题改写成一个「不依赖上下文也能独立成立」的完整问句，
            专门用于在知识库中做检索。
            规则：
            1. 只输出改写后的问句，不要解释、不要加引号、不要换行；
            2. 保留原问题中的关键实体、数字、时间、限定词；
            3. 如果本轮问题本身已经完整、不含指代或省略，原样返回。
            """;

    private final ChatModel chatModel;

    public QueryRewriter(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public String rewrite(String question, List<ChatMessage> history)
    {
        if(history == null || history.isEmpty())
        return question;
        try
        {
            List<ChatMessage> messages = new ArrayList<>(history.size() + 2);
            messages.add(SystemMessage.from(SYSTEM_PROMPT));
            messages.addAll(history);
            messages.add(UserMessage.from(question));
            String rewritten = chatModel.chat(messages).aiMessage().text();
            if (rewritten == null) {
                return question;
            }
            String trimmed = rewritten.strip();
            return trimmed.isEmpty() ? question : trimmed;
        }
        catch(Exception e)
        {
            return question;
        }
    }
}
