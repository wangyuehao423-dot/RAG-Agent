package com.ragagent.controller;

import com.ragagent.agent.ReactAgent;
import com.ragagent.dto.AgentAnswer;
import com.ragagent.dto.ChatRequest;
import com.ragagent.dto.StreamOutcome;
import com.ragagent.dto.StreamedAnswer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController 
@RequestMapping ("/api/chat")
public class ChatController {
    private final ReactAgent agent;
    public ChatController(ReactAgent agent)
    {
        this.agent = agent;
    }
    @PostMapping 
    public Map<String, Object> chat(@RequestBody ChatRequest req)
    {
        String question = validateQuestion(req);
        String cid = resolveConversationId(req.conversationId());

        AgentAnswer a = agent.answer(question, cid);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("conversationId", cid);
        body.put("answer", a.answer());
        body.put("sources", a.sources());
        body.put("refused", a.refused());
        body.put("promptTokens", a.promptTokens());
        body.put("completionTokens", a.completionTokens());
        return body;
    }
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody ChatRequest req) {
        String question = validateQuestion(req);
        String cid = resolveConversationId(req.conversationId());

        // ---- 第 1 步：建 Emitter，超时设 0（不超时）----
        // 不设 60 秒的原因：大模型生成长回答 + 工具调用循环可能超过 60 秒，
        // 设了超时会导致回答到一半连接被断，前端只收到半截内容。
        SseEmitter emitter = new SseEmitter(0L);

        // ---- 第 2 步：拿到 StreamedAnswer（此时流还没开始跑，tokens 是惰性的）----
        StreamedAnswer sa = agent.stream(question, cid);

        // ---- 第 2.5 步：先把「检索到什么」推给前端 ----
        // 检索在 agent.stream() 里已经同步跑完（通常 200 ms 内），而模型首 token 要等
        // 20~40 秒。这中间前端只能显示一个闪烁光标，演示时看起来就是「点了没反应」。
        // 所以这里立刻发一个 stage 事件把命中条数告诉前端：200 毫秒内就有可见进度，
        // 后面的长等待也有了「正在生成」的解释，而不是让人怀疑按钮没生效。
        Map<String, Object> stage = new LinkedHashMap<>();
        stage.put("hits", sa.sources() == null ? 0 : sa.sources().size());
        stage.put("refused", sa.refused());
        stage.put("message", sa.refused()
                ? "未检索到足够相关的资料，将直接给出提示"
                : "已检索到 " + (sa.sources() == null ? 0 : sa.sources().size()) + " 条相关资料，模型正在生成回答");

        // ---- 第 3 步：把「发事件 + 订阅 token 流」整体交给异步线程执行 ----
        //
        // 【为什么必须异步，这一步直接决定流式能不能被看见】
        // Flux.create 的生成逻辑默认在**订阅线程**上同步执行：只要在这条线程上调用
        // subscribe()，Reactor 就会一直跑到整个 token 流结束才返回。
        // 而此刻 controller 方法还没返回，Spring 尚未完成 SseEmitter 的 initialize，
        // 所有 send() 都只会进 earlySendAttempts 缓存；等方法返回、缓存被一次性 flush 时，
        // 模型早就答完了。实测：服务端日志记录首字 13.5 秒，客户端却在第 27.8 秒
        // 收到第一个字；一次 49 秒的回答，客户端 49 秒才看到所有事件——
        // 流式协议形同虚设，用户观感就是「点了半天，然后答案整段蹦出来」。
        // 交给独立线程后，controller 立即返回、容器立刻 initialize、send 即时 flush，
        // 前端的首字时间就等于服务端真实的首 token 时间。
        Schedulers.boundedElastic().schedule(() -> {
            // 提前 send 失败不能让整个流挂掉：前端拿不到 stage 只是少一行提示，不影响正文。
            try {
                send(emitter, "stage", stage);
            } catch (Exception ignored) {
                // 忽略：继续走正文
            }
            // 订阅期抛出的异常（模型在流开始前就失败）必须转成 SSE error 事件，
            // 不能让它逃出去把响应打成 500 JSON。
            try {
                sa.tokens().subscribe(
                        chunk -> send(emitter, "text", chunk),   // 每个 token -> 一个 text 事件
                        e -> fail(emitter, e),                   // 出错 -> 让前端知道，而不是静默挂住
                        () -> {
                            // ---- 第 4 步：流结束，发 done 事件（此时 outcome 才有最终值）----
                            // 结束事件从 outcome 读取「流结束后才确定」的最终结果：
                            // 模型可能主动拒答（需清空引用），token 用量也只在流末尾返回。
                            // 可靠性：同一条订阅链上，Agent 的 doOnComplete 一定先于这里的 onComplete
                            // 执行（Reactor 的信号传播语义）；两者可能不同线程，
                            // 所以 StreamOutcome 的字段用 volatile 保证跨线程可见。
                            StreamOutcome outcome = sa.outcome();
                            Map<String, Object> meta = new LinkedHashMap<>();
                            meta.put("conversationId", cid);
                            meta.put("sources", outcome.getSources());
                            meta.put("refused", outcome.isRefused());
                            meta.put("promptTokens", outcome.getPromptTokens());
                            meta.put("completionTokens", outcome.getCompletionTokens());
                            send(emitter, "done", meta);
                            emitter.complete();
                        });
            } catch (Exception e) {
                fail(emitter, e);
            }
        });

        return emitter;
    }
    private String validateQuestion(ChatRequest req) {
        if (req == null || req.question() == null || req.question().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question 不能为空");
        }
        return req.question().strip();
    }
    private void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }
    // 流式错误必须以 SSE error 事件告知前端，不能用 500 JSON 打断流。
    // 注意不能依赖 emitter.completeWithError：它在「响应尚未提交」时（模型调用
    // 在发出任何事件前就失败，正是最需要兜底的场景）不会发 error 事件，而是把
    // 响应打成 500 JSON。所以这里显式 send 一个 error 事件（send 本身会先提交
    // 响应），再正常 complete，任何情况下都不让异常逃出控制器。
    private void fail(SseEmitter emitter, Throwable e) {
        try {
            emitter.send(SseEmitter.event().name("error").data(rootMessage(e)));
            emitter.complete();
        } catch (IOException ex) {
            try {
                emitter.completeWithError(ex);
            } catch (Exception ignored) {
                // 连接已断，前端收不到任何事件，只能放弃
            }
        }
    }
    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null) {
            cur = cur.getCause();
        }
        String m = cur.getMessage();
        return (m == null || m.isBlank()) ? cur.toString() : m;
    }
     private String resolveConversationId(String cid) {
        return (cid == null || cid.isBlank()) ? UUID.randomUUID().toString() : cid;
    }
}
