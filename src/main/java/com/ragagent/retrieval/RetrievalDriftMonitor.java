package com.ragagent.retrieval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 检索漂移监控：让「阈值失效」这件事可被发现，而不是等用户投诉答得不对。
 *
 * <h3>为什么阈值一定要有监控</h3>
 * 阈值是「背景分布的 P90 在当前语料上的落点」。它一旦偏了，症状是安静的：
 * 该答的拒了、不该答的放了，日志里什么都不报，只有用户觉得「这系统有点笨」。
 * 所以判据不能只靠一次离线标定，还要有线上指标持续看。
 *
 * <h3>看三个信号</h3>
 * <ol>
 *   <li><b>拒答率</b>：突然变高 = 阈值过严或语料换了；接近 0 = 阈值过松、等于没有闸</li>
 *   <li><b>top1 分数分布</b>：P90 相对标定值偏移过大，说明语料/模型变了</li>
 *   <li><b>语料指纹</b>：文档数或分块数变了，说明知识库动过，标定该重跑</li>
 * </ol>
 *
 * <p>只告警、自动改阈值：分数分布漂了不代表新阈值就该是某个数，
 * 自动改会把「模型这次回答得差」误判成「阈值该调」，反而更危险。
 */
@Service
public class RetrievalDriftMonitor {

    private static final Logger log = LoggerFactory.getLogger(RetrievalDriftMonitor.class);

    /** 滑动窗口大小：500 次检索。够看出趋势，又不会把一周前的分布混进来 */
    private static final int WINDOW_SIZE = 500;

    /** 样本少于这个数不做结论：几条查询算出来的拒答率没有意义 */
    private static final int MIN_SAMPLES = 50;

    private record Sample(double similarity, boolean refused) {
    }

    private final Deque<Sample> window = new ConcurrentLinkedDeque<>();
    private final ThresholdService thresholds;

    public RetrievalDriftMonitor(ThresholdService thresholds) {
        this.thresholds = thresholds;
    }

    /** 每次检索后调一次：记下 top1 分数与这次是否拒答 */
    public void record(double topSimilarity, boolean refused) {
        window.addLast(new Sample(topSimilarity, refused));
        while (window.size() > WINDOW_SIZE) {
            window.pollFirst();
        }
    }

    public record DriftStats(
            int samples,
            double refusalRate,
            double avgSimilarity,
            double p90Similarity,
            double currentThreshold,
            String corpusFingerprint,
            String calibratedFingerprint,
            String verdict
    ) {
    }

    public DriftStats stats() {
        List<Sample> samples = new ArrayList<>(window);
        if (samples.isEmpty()) {
            return new DriftStats(0, 0, 0, 0, thresholds.similarity(),
                    thresholds.currentCorpusFingerprint(), thresholds.corpusFingerprint(), "暂无样本");
        }
        long refused = samples.stream().filter(Sample::refused).count();
        List<Double> sims = samples.stream().map(Sample::similarity).toList();

        String fingerprint = thresholds.currentCorpusFingerprint();
        String calibrated = thresholds.corpusFingerprint();
        String verdict;
        if (calibrated != null && !calibrated.equals(fingerprint)) {
            verdict = "语料已变化（文档/分块数与标定时不一致），建议重跑标定";
        } else if (samples.size() < MIN_SAMPLES) {
            verdict = "样本不足，暂不下结论";
        } else {
            double rate = (double) refused / samples.size();
            if (rate > 0.5) {
                verdict = "拒答率偏高，阈值可能过严或语料不匹配";
            } else if (rate < 0.02) {
                verdict = "几乎不拒答，阈值可能过松";
            } else {
                verdict = "正常";
            }
        }

        return new DriftStats(
                samples.size(),
                (double) refused / samples.size(),
                sims.stream().mapToDouble(Double::doubleValue).average().orElse(0),
                ThresholdCalibrator.percentile(sims, 0.90),
                thresholds.similarity(),
                fingerprint,
                calibrated,
                verdict);
    }

    /** 每 5 分钟巡检一次：只打日志告警，不自动改阈值 */
    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    public void check() {
        DriftStats s = stats();
        if (s.samples() < MIN_SAMPLES) {
            return;
        }
        if (!"正常".equals(s.verdict())) {
            log.warn("检索漂移告警：{} | samples={} 拒答率={}% top1P90={} 当前阈值={}",
                    s.verdict(), s.samples(),
                    Math.round(s.refusalRate() * 100), round(s.p90Similarity()), round(s.currentThreshold()));
            return;
        }
        // 分布偏移单独看：拒答率正常但分数整体下移，说明语料结构变了
        double shift = Math.abs(s.p90Similarity() - s.currentThreshold());
        if (shift > 0.15) {
            log.warn("检索分数分布偏移：top1P90={} 与阈值 {} 相差 {}，建议重跑标定",
                    round(s.p90Similarity()), round(s.currentThreshold()), round(shift));
        }
    }

    private static double round(double v) {
        return Math.round(v * 1000d) / 1000d;
    }
}
