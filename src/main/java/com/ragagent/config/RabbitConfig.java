package com.ragagent.config;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 消息队列拓扑：文档级（拆段）+ 段级（入库）+ 各自的重试/死信 + 异步摘要队列。
 *
 * <h3>为什么入库要 MQ，而不是 @Async 线程池</h3>
 * 线程池跑的任务在 JVM 崩溃时直接蒸发，documents 会永久卡在「处理中」，
 * 只能靠「启动时扫库对账」这种一次性、只在重启时跑一次的兜底去捞。
 * MQ 的手动 ack 把「确认」挪到业务成功之后，ack 前任何一步崩溃消息都会自动重投——
 * 崩溃恢复变成持续的、逐条的兜底。
 *
 * <h3>为什么分两级队列</h3>
 * 上传的那一刻还不知道会切出多少块，必须先解析才能拆段，所以第一级消息只做
 * 「解析 + 分块 + 拆段 + 登记 + 投段消息」，第二级消息才是真正的向量化与入库。
 * 一段一条消息，而不是整篇一条：整篇一条消息会跑十几分钟，prefetch 一占其他文档全排队，
 * MQ 重投等于整篇重来，分段就白做了。
 *
 * <h3>为什么重试走「延迟队列」而不是直接 nack(requeue=true)</h3>
 * 直接 requeue 会让消息立刻回到队首，形成热循环：一个正在抖动的 embedding 接口
 * 会被瞬间打满三次、三次全失败进死信，重试等于没重试。
 * 延迟队列把重试摊开在时间轴上（TTL 到期后经死信交换机回到主队列），
 * 给下游留出恢复窗口。语义上仍然是「nack → 重新投递」，只是重投有时间间隔。
 */
@Configuration
public class RabbitConfig {

    /** 文档级主队列：上传接口投递，消费者在这里做解析 + 拆段 */
    public static final String PLAN_QUEUE = "rag.ingestion.plan.queue";
    public static final String PLAN_RETRY_QUEUE = "rag.ingestion.plan.retry.queue";
    public static final String PLAN_DEAD_QUEUE = "rag.ingestion.plan.dead.queue";

    /** 段级主队列：一段一条消息，消费者在这里做向量化 + 写库 + 段收敛 */
    public static final String SEGMENT_QUEUE = "rag.ingestion.segment.queue";
    public static final String SEGMENT_RETRY_QUEUE = "rag.ingestion.segment.retry.queue";
    public static final String SEGMENT_DEAD_QUEUE = "rag.ingestion.segment.dead.queue";

    /** 异步摘要队列：每轮问答结束后投递，消费者压缩历史并推进游标 */
    public static final String SUMMARY_QUEUE = "rag.memory.summary.queue";

    /** 重试间隔：10 秒。够一次临时抖动恢复，又不会让用户觉得文档卡住 */
    private static final int RETRY_TTL_MS = 10_000;

    @Bean
    public Queue planQueue() {
        return QueueBuilder.durable(PLAN_QUEUE).build();
    }

    @Bean
    public Queue planRetryQueue() {
        return delayQueue(PLAN_RETRY_QUEUE, PLAN_QUEUE);
    }

    @Bean
    public Queue planDeadQueue() {
        return QueueBuilder.durable(PLAN_DEAD_QUEUE).build();
    }

    @Bean
    public Queue segmentQueue() {
        return QueueBuilder.durable(SEGMENT_QUEUE).build();
    }

    @Bean
    public Queue segmentRetryQueue() {
        return delayQueue(SEGMENT_RETRY_QUEUE, SEGMENT_QUEUE);
    }

    @Bean
    public Queue segmentDeadQueue() {
        return QueueBuilder.durable(SEGMENT_DEAD_QUEUE).build();
    }

    @Bean
    public Queue summaryQueue() {
        return QueueBuilder.durable(SUMMARY_QUEUE).build();
    }

    /**
     * 延迟重试队列：本身没有消费者，只靠 TTL + 死信交换机做「定时搬运」。
     *
     * <p>deadLetterExchange 传空串 = 默认交换机（direct），routingKey 直接写目标队列名，
     * 这样消息到期后就回到主队列，不需要额外声明交换机和绑定。
     */
    private static Queue delayQueue(String name, String targetQueue) {
        return QueueBuilder.durable(name)
                .withArgument("x-message-ttl", RETRY_TTL_MS)
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", targetQueue)
                .build();
    }
}
