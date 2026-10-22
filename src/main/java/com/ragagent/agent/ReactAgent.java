package com.ragagent.agent;

import com.ragagent.dto.AgentAnswer;
import com.ragagent.dto.Source;
import com.ragagent.dto.StreamOutcome;
import com.ragagent.dto.StreamedAnswer;
import com.ragagent.observability.TraceLogger;
import com.ragagent.retrieval.HybridRetriever;
import com.ragagent.retrieval.QueryRewriter;
import com.ragagent.retrieval.RetrievalResult;
import com.ragagent.util.ModelCallGuard;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.TokenUsage;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.agent.Agent;
import org.bsc.langgraph4j.agentexecutor.AgentExecutor;
import org.bsc.langgraph4j.streaming.StreamingOutput;
import org.bsc.langgraph4j.streaming.StreamingOutputEnd;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Service
public class ReactAgent {
    private static final String REFUSAL_TEXT = "抱歉，知识库中暂未检索到相关内容，无法回答该问题。";

    private static final String SYSTEM_PROMPT = """
            你是一个严谨的企业知识库问答助手。请严格遵守以下规则：
            1. 只依据下方提供的检索内容作答，并在句末用 [来源: 文档名] 标注引用。
            2. 若检索内容与问题确实无关或不足以回答，只输出这一句、不要输出任何其他内容：
               %s
            3. 若检索内容足以回答，必须直接给出答案，不要拒答，也不要输出"本该回答"之类的元说明。
            4. 若现有内容不足以回答，可再次调用 searchKnowledgeBase 工具补充检索。
            5. 回答简洁、准确，使用中文。
            """.formatted(REFUSAL_TEXT);
    private final CompiledGraph<AgentExecutor.State> syncGraph;
    private final CompiledGraph<AgentExecutor.State> streamGraph;
    private final ChatMemoryProvider chatMemoryProvider;
    private final HybridRetriever retriever;
    private final QueryRewriter queryRewriter;
    private final TraceLogger traceLogger;
    private final double similarityThreshold;
    private final double lexicalThreshold;

    public ReactAgent(ChatModel chatModel,
                      StreamingChatModel streamingChatModel,
                      ChatMemoryProvider chatMemoryProvider,
                      HybridRetriever retriever,
                      QueryRewriter queryRewriter,
                      SearchKnowledgeBaseTool searchTool,
                      TraceLogger traceLogger,
                      @Value("${app.retrieval.similarity-threshold:0.45}") double similarityThreshold,
                      @Value("${app.retrieval.lexical-threshold:0.05}") double lexicalThreshold) {
        // ---- 第 1 步：存下所有依赖（图之外的东西，构造后还要用）----
        this.chatMemoryProvider = chatMemoryProvider;
        this.retriever = retriever;
        this.queryRewriter = queryRewriter;
        this.traceLogger = traceLogger;
        this.similarityThreshold = similarityThreshold;
        this.lexicalThreshold = lexicalThreshold;

        SystemMessage systemMessage = SystemMessage.from(SYSTEM_PROMPT);
        // build() 与 compile() 都抛受检异常 GraphStateException。
        // 建图失败属于「启动期就注定起不来」的配置错误，转成非受检异常，
        // 避免把 langgraph4j 的异常类型泄漏到构造器签名上。
        try {
            this.syncGraph = AgentExecutor.builder()
                    .chatModel(chatModel)
                    .systemMessage(systemMessage)
                    .toolsFromObject(searchTool)
                    .build()
                    .compile();

            this.streamGraph = AgentExecutor.builder()
                    // 第二个参数 true = 流式结束时额外发出一条 StreamingOutputEnd 节点输出
                    .chatModel(streamingChatModel, true)
                    .systemMessage(systemMessage)
                    .toolsFromObject(searchTool)
                    .build()
                    .compile();
        } catch (GraphStateException e) {
            throw new IllegalStateException("构建 AgentExecutor 图失败", e);
        }
    }
    private ChatMemory memoryFor(String conversationId) {
        return chatMemoryProvider.get(conversationId);
    }
    /** 同步问答入口：默认读写对话记忆（多轮上下文）。 */
    public AgentAnswer answer(String question, String conversationId)
    {
        return answer(question, conversationId, true);
    }
    private AgentAnswer answer(String question, String conversationId, boolean withMemory)
    {
        // 计时起点：三条退出路径（低置信度拒答 / 模型拒答 / 正常回答）都要算总耗时。
        long start = System.currentTimeMillis();
        List<ChatMessage> history = withMemory ? memoryFor(conversationId).messages() : List.of();
        String searchQuery = queryRewriter.rewrite(question, history);
        List<RetrievalResult> results = retriever.search(searchQuery);
        List<Source> sources = toSources(results);

        // 第 1 道闸：检索层面就没把握 —— 连模型都不用调，直接按拒答返回。
        if (isLowConfidence(results)) {
            if (withMemory) {
                memoryFor(conversationId).add(List.of(UserMessage.from(question), AiMessage.from(REFUSAL_TEXT)));
            }
            traceLogger.record(conversationId, "refuse-low-confidence", question, REFUSAL_TEXT, 0, 0,
                    System.currentTimeMillis() - start);
            return new AgentAnswer(REFUSAL_TEXT, List.of(), 0, 0, true);
        }

        // 第 2 道闸：检索有内容，交给 Agent 生成答案（它还能自己再调一次检索工具补充）。
        AgentExecutor.State modelState = ModelCallGuard.call("chat",
                () -> syncGraph.invoke(buildAgentInput(history, results, question))
                        .orElseThrow(() -> new IllegalStateException("Agent 执行未产出终态")));
        String answer = modelState.finalResponse().orElse("");
        // 模型「调用成功但内容为空」= 上游异常产出，不能当作拒答返回给用户；
        // 抛给 ModelCallGuard 重试层，重试仍失败则按上游错误提示。
        if (answer.isBlank()) {
            throw new IllegalStateException("模型未返回任何内容");
        }

        // 模型可能自己判定「答不了」并原样吐出拒答句，此时引用列表要清空，
        // 否则前端会展示一堆与答案无关的来源。
        boolean modelRefused = isRefusal(answer);
        List<Source> finalSources = modelRefused ? List.of() : sources;
        if (withMemory) {
            memoryFor(conversationId).add(List.of(UserMessage.from(question), AiMessage.from(answer)));
        }
        // 同步路径的 token 用量先按 0 记：langgraph4j 的 CallModel 只把 AI 消息与最终文本
        // 回写进 state，ChatResponse（usage 在里面）没有保留下来；usage 只在流式路径的
        // StreamingOutputEnd 元数据里能拿到，等流式方法接上后再补真实值。
        traceLogger.record(conversationId, modelRefused ? "refuse-by-model" : "answer", question, answer,
                0, 0, System.currentTimeMillis() - start);
        return new AgentAnswer(answer, finalSources, 0, 0, modelRefused);
    }
    private Map<String, Object> buildAgentInput(List<ChatMessage> history,
                                                List<RetrievalResult> results, String question) {
        // 预分配 history.size() + 1：只多加"本轮"这一条（system 由框架补，不在这里）。
        List<ChatMessage> messages = new ArrayList<>(history.size() + 1);
        messages.addAll(history);
        // 顺序是【检索结果】在前、【用户问题】在后 —— 让模型生成时"离得最近的输入是问题"，
        // 不容易答非所问。反过来（先问题后资料）时，模型读完资料有时会忘了问题是什么。
        messages.add(UserMessage.from("【知识库检索结果】\n" + buildContext(results) + "\n【用户问题】\n" + question));
        // 图在构造时已注册好工具与 system，入参只需要给 messages ——「编译一次、多次复用」。
        return Map.of("messages", messages);
    }
    private boolean isLowConfidence(List<RetrievalResult> results)
    {
        if(results.isEmpty())
        {
            return true;
        }
        RetrievalResult top = results.get(0);
        return top.similarity() < similarityThreshold && top.lexicalScore() < lexicalThreshold;
    }
    private static boolean isRefusal(String answer) {
        // 只有模型按 system prompt 明确吐出拒答句才算拒答。
        // 空/空白回答不是拒答：那是上游调用异常（没产出内容），
        // 若按拒答处理，前端会显示「已拒答」误导用户，且没有重试机会。
        return answer != null && answer.contains(REFUSAL_TEXT);
    }
    private List<Source> toSources(List<RetrievalResult> results) {
        // 转成对外的 Source 而不是直接返回内部对象，三个原因：
        //   1. rrfScore 是内部排序用的，不该暴露给前端；
        //   2. content 是完整片段（可能几百字），前端列表只要摘要，顺手截成 snippet；
        //   3. 两个分数（similarity / lexicalScore）都要带给前端，
        //      否则只被词法命中的片段会显示成"相关度 0%"的假象。
        return results.stream()
                .map(r -> new Source(r.sourceName(), r.similarity(), r.lexicalScore(), snippet(r.content())))
                .toList();
    }
    private String buildContext(List<RetrievalResult> results) {
        // 拼给模型看的上下文：编号 [1] [2] 让模型引用时说"第 1 条"，
        // 也帮它区分多个片段、减少把两段内容混在一起。
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            RetrievalResult r = results.get(i);
            sb.append('[').append(i + 1).append("] 来源：").append(r.sourceName()).append('\n')
                    .append(r.content()).append("\n\n");
        }
        return sb.toString();
    }
    private static String snippet(String content) {
        if (content == null) {
            return "";
        }
        // 120 字是「够看出是不是相关内容」和「不撑爆前端列表」之间的折中。
        return content.length() > 120 ? content.substring(0, 120) + "…" : content;
    }
    public StreamedAnswer stream(String question, String conversationId)
    {
        long start = System.currentTimeMillis();
        List<ChatMessage> history = memoryFor(conversationId).messages();
        String searchQuery = queryRewriter.rewrite(question, history);
        List<RetrievalResult> results = retriever.search(searchQuery);
        List<Source> sources = toSources(results);
        StreamOutcome outcome = new StreamOutcome();
        if(isLowConfidence(results))
        {
            memoryFor(conversationId).add(List.of(UserMessage.from(question),AiMessage.from(REFUSAL_TEXT)));
            traceLogger.record(conversationId, "refuse-low-confidence", question, REFUSAL_TEXT, 0, 0, System.currentTimeMillis() - start);
            outcome.setRefused(true);
            outcome.setSources(List.of());
            return new StreamedAnswer(List.of(), true, Flux.just(REFUSAL_TEXT), outcome);
        }
        outcome.setSources(sources);
        StringBuffer answerBuffer = new StringBuffer();
        int[] usage = {0, 0};
        Map<String, Object> input = buildAgentInput(history, results, question);

        // ---- 第 5 步：用 Flux.create 把「阻塞式遍历图输出」桥接成响应式 Flux ----
        // 这样 Controller 仍能按原来的 SSE 方式消费同一份数据，一行都不用改。
        // 注意 streamGraph.stream(input) 返回的是 AsyncGenerator（不是 java.util.stream.Stream），
        // 它本身不可关闭；调它的 stream() 取到 Stream 才能用 try-with-resources 与 Iterator。
        // 资源获取（streamGraph.stream()）也必须在 try 内：图节点可能在这里就同步执行、
        // 直接抛模型异常；若放资源初始化表达式里，Java 的 try-with-resources 不会保护它，
        // 异常会绕过 catch 逃出 lambda，把 SSE 流打成 500 JSON。
        Flux<String> tokens = Flux.create(sink -> {
            Stream<NodeOutput<AgentExecutor.State>> nodeStream = null;
            try {
                nodeStream = streamGraph.stream(input).stream();
                Iterator<NodeOutput<AgentExecutor.State>> it = nodeStream.iterator();
                while (it.hasNext()) {
                    NodeOutput<AgentExecutor.State> output = it.next();
                    if (output instanceof StreamingOutputEnd<?> end) {
                        // 收尾节点：只在这里取 token 用量，不再向外发文本
                        // （完整文本早已按 token 逐片发出，再发一遍就重复了）。
                        ChatResponseMetadata meta = end.metadata("chatResponseMetadata")
                                .map(m -> (ChatResponseMetadata) m).orElse(null);
                        if (meta != null && meta.tokenUsage() != null) {
                            usage[0] = meta.tokenUsage().inputTokenCount();
                            usage[1] = meta.tokenUsage().outputTokenCount();
                        }
                    } else if (output instanceof StreamingOutput<?> so) {
                        String chunkText = so.chunk();
                        if (chunkText != null && !chunkText.isEmpty()) {
                            // 一边累积完整回答，一边立刻往外发（交给下游做 SSE）。
                            // 过滤空片：否则会往 SSE 里发一个空事件，前端看起来像"卡了一下又没内容"。
                            answerBuffer.append(chunkText);
                            sink.next(chunkText);
                        }
                    }
                }
        // ---- 第 6 步：流结束后统一收尾（与同步路径完全一致）----
                String answer = answerBuffer.toString();
                // 流正常结束但没产出任何内容：同样是上游异常（空回答曾被打成
                // 「已拒答」误导用户），抛给 sink.error，前端收到 error 事件后
                // 自动回退同步接口重试，而不是显示「已拒答」。
                if (answer.isBlank()) {
                    throw new IllegalStateException("模型未返回任何内容");
                }
                boolean modelRefused = isRefusal(answer);
                outcome.setRefused(modelRefused);
                outcome.setSources(modelRefused ? List.of() : sources);
                outcome.setUsage(usage[0], usage[1]);
                memoryFor(conversationId).add(List.of(UserMessage.from(question), AiMessage.from(answer)));
                traceLogger.record(conversationId, modelRefused ? "refuse-by-model" : "answer", question, answer,
                        usage[0], usage[1], System.currentTimeMillis() - start);

                sink.complete();
            } catch (Exception e) {
                // sink.error 在订阅者已终止（如连接断开）时会再抛一次；
                // 此时错误无处可投递，必须吞掉，绝不能让异常逃出 lambda。
                try {
                    sink.error(e);
                } catch (Exception ignored) {
                }
            } finally {
                // 流失败后关闭 AsyncGenerator 的 Stream 可能再次抛出同一个模型异常
                // （关闭时内部 join 同一个失败 Future）；此时 sink 已因上面的
                // sink.error 终止，不能再让 close 的异常逃出 lambda。
                if (nodeStream != null) {
                    try {
                        nodeStream.close();
                    } catch (Exception ignored) {
                        // 已在 catch 里通过 sink.error 通知下游，这里只需保证不逃逸
                    }
                }
            }
        }, FluxSink.OverflowStrategy.BUFFER);

        // 注意第二个参数硬编码 false：它的含义是"流开始前是否已因低置信度拒答"，
        // 走到这里说明没走上面的拒答分支。真正的拒答状态在 outcome 里，Controller 读 outcome。
        return new StreamedAnswer(sources, false, tokens, outcome);   
    }
}
