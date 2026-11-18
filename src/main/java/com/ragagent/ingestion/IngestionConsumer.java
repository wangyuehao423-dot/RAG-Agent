package com.ragagent.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.ragagent.config.RabbitConfig;
import com.ragagent.exception.ModelRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 入库消费者：两个监听方法，对应两级队列。
 *
 * <h3>为什么用「手动 ack」而不是自动确认</h3>
 * 自动确认的语义是「消息一交给消费者就算消费成功」——消费者随后崩了，消息已经没了，
 * 那份文档永远卡在「处理中」。手动 ack 把确认挪到业务成功之后：
 * ack 之前任何一步崩溃，消息都因为没确认而被 broker 重新投递。
 *
 * <h3>失败分两类，处理不一样</h3>
 * <ul>
 *   <li><b>可重试</b>（embedding 抖动、超时、5xx）→ 投延迟重试队列，TTL 到期回主队列重跑</li>
 *   <li><b>不可重试</b>（文件损坏、编码解析失败）→ 置 FAILED 写原因 + ack，
 *       不重投——文件已经坏了，重投一百次还是坏，只会死循环</li>
 * </ul>
 * 这两种都必须 ack：不 ack 的后果是消息一直躺在 unacked 里，
 * 直到连接断开才被重投，等于把「不可重试失败」也变成了无限重试。
 */
@Component
public class IngestionConsumer {

    private static final Logger log = LoggerFactory.getLogger(IngestionConsumer.class);

    private final IngestionService ingestion;
    private final IngestionProducer producer;
    private final ObjectMapper mapper;

    public IngestionConsumer(IngestionService ingestion, IngestionProducer producer, ObjectMapper mapper) {
        this.ingestion = ingestion;
        this.producer = producer;
        this.mapper = mapper;
    }

    /* ==================== 文档级：解析 + 拆段 ==================== */

    @RabbitListener(queues = RabbitConfig.PLAN_QUEUE)
    public void onPlan(String payload, Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        IngestionTask task = null;
        try {
            task = mapper.readValue(payload, IngestionTask.class);
            ingestion.plan(task);
            channel.basicAck(tag, false);
        } catch (PermanentIngestionException e) {
            log.error("文档不可重试失败，置 FAILED 后 ack: {}", payload, e);
            if (task != null) {
                ingestion.markDocumentFailed(task, e.getMessage());
            }
            channel.basicAck(tag, false);
        } catch (Exception e) {
            log.warn("文档拆段失败，准备重试: {}", payload, e);
            retryOrDeadPlan(task, channel, tag);
        }
    }

    private void retryOrDeadPlan(IngestionTask task, Channel channel, long tag) throws IOException {
        if (task == null) {
            // 消息体本身无法反序列化：重投也还是解析不了，直接进死信留现场
            producer.deadLetterPlan(new IngestionTask("unknown", "unknown", "", 99));
            channel.basicAck(tag, false);
            return;
        }
        if (task.retryCount() >= IngestionTask.MAX_RETRY) {
            // 重试超限：置 FAILED 并进死信。不能置 DONE——那样用户会检索到一篇残缺文档
            ingestion.markDocumentFailed(task, "重试 " + task.retryCount() + " 次仍失败");
            producer.deadLetterPlan(task);
        } else {
            producer.retryPlanLater(task.nextAttempt());
        }
        channel.basicAck(tag, false);
    }

    /* ==================== 段级：向量化 + 写库 ==================== */

    @RabbitListener(queues = RabbitConfig.SEGMENT_QUEUE)
    public void onSegment(String payload, Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        SegmentTask task = null;
        try {
            task = mapper.readValue(payload, SegmentTask.class);
            ingestion.ingestSegment(task);
            channel.basicAck(tag, false);
        } catch (ModelRequestException e) {
            // 请求被拒（401/400/404）：重试多少次结果都一样，属于配置问题，
            // 立刻置 FAILED 并 ack，不要白等三轮。
            log.error("段入库被上游拒绝，不可重试: {}", payload, e);
            if (task != null) {
                ingestion.markSegmentFailed(task, e.getMessage());
            }
            channel.basicAck(tag, false);
        } catch (PermanentIngestionException e) {
            log.error("段入库不可重试失败: {}", payload, e);
            if (task != null) {
                ingestion.markSegmentFailed(task, e.getMessage());
            }
            channel.basicAck(tag, false);
        } catch (Exception e) {
            log.warn("段入库失败，准备重试: seq={} {}", task == null ? -1 : task.seq(), payload, e);
            retryOrDeadSegment(task, channel, tag);
        }
    }

    private void retryOrDeadSegment(SegmentTask task, Channel channel, long tag) throws IOException {
        if (task == null) {
            channel.basicAck(tag, false);
            return;
        }
        if (task.retryCount() >= SegmentTask.MAX_RETRY) {
            // 这一段彻底失败：整篇置 FAILED，但已完成段保留。
            // 反过来置 DONE 会让用户检索到一篇只剩部分章节的文档——
            // 残缺但看起来正常，比明确失败更糟。
            ingestion.markSegmentFailed(task, "重试 " + task.retryCount() + " 次仍失败");
            producer.deadLetterSegment(task);
        } else {
            producer.retrySegmentLater(task.nextAttempt());
        }
        channel.basicAck(tag, false);
    }
}
