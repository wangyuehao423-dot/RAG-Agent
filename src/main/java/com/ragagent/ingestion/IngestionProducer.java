package com.ragagent.ingestion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.config.RabbitConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 入库任务投递器：文档级 / 段级两条线，各自有主队列、延迟重试队列、死信队列。
 *
 * <p>消息用 JSON 字符串而不是 Java 序列化：消费者可能被换成别的语言实现，
 * 出问题时在管理台里也能直接看懂消息内容。
 */
@Component
public class IngestionProducer {

    private static final Logger log = LoggerFactory.getLogger(IngestionProducer.class);

    private final RabbitTemplate rabbit;
    private final ObjectMapper mapper;

    public IngestionProducer(RabbitTemplate rabbit, ObjectMapper mapper) {
        this.rabbit = rabbit;
        this.mapper = mapper;
    }

    /* ---------------- 文档级：解析 + 拆段 ---------------- */

    /** 首次投递：上传接口调它，投完立刻回 202 */
    public void publishPlan(IngestionTask task) {
        send(RabbitConfig.PLAN_QUEUE, task);
    }

    public void retryPlanLater(IngestionTask task) {
        send(RabbitConfig.PLAN_RETRY_QUEUE, task);
    }

    public void deadLetterPlan(IngestionTask task) {
        send(RabbitConfig.PLAN_DEAD_QUEUE, task);
    }

    /* ---------------- 段级：向量化 + 写库 ---------------- */

    public void publishSegment(SegmentTask task) {
        send(RabbitConfig.SEGMENT_QUEUE, task);
    }

    public void retrySegmentLater(SegmentTask task) {
        send(RabbitConfig.SEGMENT_RETRY_QUEUE, task);
    }

    public void deadLetterSegment(SegmentTask task) {
        send(RabbitConfig.SEGMENT_DEAD_QUEUE, task);
    }

    private void send(String routingKey, Object task) {
        try {
            String payload = mapper.writeValueAsString(task);
            rabbit.convertAndSend(routingKey, payload, message -> {
                // persistent：broker 重启后消息不丢。默认就是 persistent，
                // 这里显式写出来，是为了让「这条消息必须活过重启」这件事在代码里可见。
                message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                return message;
            });
        } catch (JsonProcessingException e) {
            // 载荷是 record + 基本类型，序列化失败属于程序错误，直接抛
            throw new IllegalStateException("无法序列化入库任务", e);
        }
    }
}
