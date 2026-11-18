package com.ragagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.util.ModelCallGuard;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 拒答判定：用 function calling 强制模型交出 {@code refused} 布尔字段。
 *
 * <h3>为什么不用「拿回答去和固定话术比文字」</h3>
 * 模型是概率生成，同一种拒答意图有无数种说法（「没检索到」「库里没有」「资料不足」），
 * 甚至会出现「先说没有再补充」的折中文本。比对文字就是在跟随机性较劲：
 * 用「整体相等」会漏掉所有改写，用「包含」又会把折中文本误判成拒答。
 * function calling 把这个意图收敛成一个布尔字段，schema 在模型服务侧校验，
 * 拿到的一定是合法 JSON——判断从「猜文本」变成「读字段」。
 *
 * <h3>为什么必须强制 toolChoice=REQUIRED</h3>
 * 只注册工具不强制，模型随时可以绕过工具直接吐文本，refused 字段就没了，
 * 等于又退化成文字比对。REQUIRED 才让「必须表态」成为协议约束，而不是提示词建议。
 *
 * <h3>为什么 answer 取图生成的原文，而不是取工具返回的 answer</h3>
 * 收尾这次调用只用来「表态」。答案正文已经在 ReAct 图里生成好了，
 * 再让模型复述一遍会引入语义漂移（它可能顺手改写、缩短、补充），
 * 而用户看到的应该是检索链路真正产出的那一份。
 */
@Component
public class RefusalArbiter {

    private static final Logger log = LoggerFactory.getLogger(RefusalArbiter.class);

    /** 工具名固定为 respond：提示词、日志、面试口径都对齐这一个名字 */
    public static final String TOOL_NAME = "respond";

    /**
     * 与 ReAct 图 system prompt 里规定的拒答话术完全一致。
     * 放在这里是为了让「话术定义」和「命中判定」同源——文案改一处即可，不会漂移。
     */
    public static final String REFUSAL_TEXT = "抱歉，知识库中暂未检索到相关内容，无法回答该问题。";

    private static final String SYSTEM_PROMPT = """
            你是一个严格的内容审核器。用户会给你一个问题，以及助手针对它的回答。
            你必须调用 respond 工具，判断这条回答是否属于「因为知识库中没有相关内容，所以拒答」。
            - refused=true：助手的回答本质是「没找到 / 资料不足 / 无法回答」，没有给出实质内容。
            - refused=false：助手给出了实质回答，哪怕回答不完整、哪怕它自己也说了一句「仅供参考」。
            answer 字段原样回填助手答案，不要改写、不要补充、不要解释。
            """;

    private static final ToolSpecification RESPOND_TOOL = ToolSpecification.builder()
            .name(TOOL_NAME)
            .description("提交对这条回答的拒答判定结果")
            .parameters(JsonObjectSchema.builder()
                    .addStringProperty("answer", "助手给出的回答原文")
                    .addBooleanProperty("refused", "是否因为知识库中没有相关内容而拒答")
                    .required("answer", "refused")
                    .build())
            .build();

    private final ChatModel chatModel;
    private final ObjectMapper mapper;

    public RefusalArbiter(ChatModel chatModel, ObjectMapper mapper) {
        this.chatModel = chatModel;
        this.mapper = mapper;
    }

    /**
     * 判定这条回答是不是拒答。
     *
     * @param question 用户问题（给审核器提供对照，避免把「答非所问」误判成拒答）
     * @param answer   ReAct 图生成的回答原文
     * @return true = 拒答，调用方应清空引用列表
     */
    public boolean isRefused(String question, String answer) {
        // ---- 快路径：模型已经照 system prompt 原样吐了拒答句 ----
        // 这种情况下意图是确定的，没必要再花一次 15~25 秒的 function calling 去确认。
        // 上游抖动时这一次调用能占到整次问答耗时的三分之一，而它换回的结论
        // 与「答案就是那句拒答话术」完全相同。注意只做「去空白后整句包含」判断：
        // 改写过的拒答（库里没有 / 资料不足）仍然走下面的 arbiter，保持判定能力不变。
        if (answer != null && !answer.isBlank()
                && strip(answer).contains(strip(REFUSAL_TEXT))) {
            log.info("拒答快路径命中（答案即规定话术），跳过 function calling 判定");
            return true;
        }

        ChatRequest request = ChatRequest.builder()
                .messages(
                        SystemMessage.from(SYSTEM_PROMPT),
                        UserMessage.from("【用户问题】\n" + question + "\n\n【助手回答】\n" + answer))
                .toolSpecifications(RESPOND_TOOL)
                // REQUIRED：模型必须走工具，不能绕过它直接输出文本
                .toolChoice(ToolChoice.REQUIRED)
                .build();

        ChatResponse response = ModelCallGuard.call("refusal-check", () -> chatModel.chat(request));
        AiMessage ai = response.aiMessage();

        if (!ai.hasToolExecutionRequests()) {
            // 理论上走不到：REQUIRED 下模型必须调用工具。
            // 真走到了（上游实现不严格遵守协议）说明这次判定不可信，按「未拒答」放行——
            // 放行的代价是多给用户一条带引用的答案，而错判成拒答会把一条好答案直接扔掉。
            log.warn("拒答判定未走工具，按未拒答处理，question={}", question);
            return false;
        }

        ToolExecutionRequest call = ai.toolExecutionRequests().get(0);
        try {
            JsonNode args = mapper.readTree(call.arguments());
            return args.path("refused").asBoolean(false);
        } catch (Exception e) {
            // arguments 是模型侧按 schema 生成的合法 JSON，解析失败属于异常情况
            log.warn("拒答判定结果解析失败，按未拒答处理: {}", call.arguments(), e);
            return false;
        }
    }

    /** 去掉所有空白字符：模型偶尔会在标点间插入换行，逐字比对会被这种噪声打穿。 */
    private static String strip(String text) {
        return text.replaceAll("\\s+", "");
    }
}
