package com.ragagent.retrieval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 拒答阈值的运行时来源：优先读标定表，没标定过才退回配置默认值。
 *
 * <h3>为什么阈值不能是一个写死的绝对数</h3>
 * 0.45 这种数跟 embedding 模型、语料结构、文档长度分布全都绑定。换一版知识库、
 * 换一个向量模型，同一个 0.45 的含义就变了——要么把能答的问题拒掉，要么把答不了的问题放进来。
 * 写死它等于承认手里是个「语料绑定的魔法数」，面试官一句「你换库就手调一次？」
 * 就能把整套实测标定的说服力拆穿。
 *
 * <h3>为什么是 P90 而不是别的分位</h3>
 * 取「背景分布（已知无关的查询）」的高分位做门槛：只有明显高于背景噪声的检索结果才放行。
 * P90 意味着按当前语料，大约 10% 的无关查询仍会越过第一道闸——这没关系，因为
 * 阈值只做粗筛，最终答不答还有第二道（模型 function calling 表态）兜着。
 * 选 P99 会让第一道闸过严，把一堆本该由模型判断的问题直接拒掉。
 */
@Service
public class ThresholdService {

    private static final Logger log = LoggerFactory.getLogger(ThresholdService.class);

    private final JdbcTemplate jdbc;
    private final double fallbackSimilarity;
    private final double fallbackLexical;

    private volatile double similarity;
    private volatile double lexical;
    private volatile String corpusFingerprint;
    private volatile int sampleSize;

    public ThresholdService(JdbcTemplate jdbc,
                            @Value("${app.retrieval.similarity-threshold:0.45}") double fallbackSimilarity,
                            @Value("${app.retrieval.lexical-threshold:0.05}") double fallbackLexical) {
        this.jdbc = jdbc;
        this.fallbackSimilarity = fallbackSimilarity;
        this.fallbackLexical = fallbackLexical;
        this.similarity = fallbackSimilarity;
        this.lexical = fallbackLexical;
    }

    /** 等 schema 初始化完成后再读表，避免 Bean 初始化早于建表脚本 */
    @EventListener(ApplicationReadyEvent.class)
    public void load() {
        try {
            List<java.util.Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT metric, value, sample_size, corpus_fingerprint FROM retrieval_thresholds");
            for (var row : rows) {
                String metric = String.valueOf(row.get("metric"));
                double value = ((Number) row.get("value")).doubleValue();
                if ("similarity".equals(metric)) {
                    similarity = value;
                    sampleSize = row.get("sample_size") == null ? 0 : ((Number) row.get("sample_size")).intValue();
                    corpusFingerprint = (String) row.get("corpus_fingerprint");
                } else if ("lexical".equals(metric)) {
                    lexical = value;
                }
            }
            if (!rows.isEmpty()) {
                log.info("已加载标定阈值 similarity={} lexical={} sample={}", similarity, lexical, sampleSize);
            } else {
                log.info("尚无标定记录，使用配置默认阈值 similarity={} lexical={}", similarity, lexical);
            }
        } catch (Exception e) {
            // 表还没建好 / 库连不上时不能让应用起不来：退回配置默认值，只是没有分位数语义
            log.warn("读取标定阈值失败，使用配置默认值", e);
        }
    }

    public double similarity() {
        return similarity;
    }

    public double lexical() {
        return lexical;
    }

    public int sampleSize() {
        return sampleSize;
    }

    public String corpusFingerprint() {
        return corpusFingerprint;
    }

    /** 标定完成后写回内存 + 落库，检索立即用新阈值，不需要重启 */
    public void apply(double newSimilarity, double newLexical, int newSampleSize, String fingerprint) {
        this.similarity = newSimilarity;
        this.lexical = newLexical;
        this.sampleSize = newSampleSize;
        this.corpusFingerprint = fingerprint;
        upsert("similarity", newSimilarity, newSampleSize, fingerprint);
        upsert("lexical", newLexical, newSampleSize, fingerprint);
        log.info("阈值已更新 similarity={} lexical={} sample={} fingerprint={}",
                newSimilarity, newLexical, newSampleSize, fingerprint);
    }

    private void upsert(String metric, double value, int n, String fingerprint) {
        jdbc.update("""
                INSERT INTO retrieval_thresholds(metric, value, sample_size, percentile, corpus_fingerprint, calibrated_at)
                VALUES (?, ?, ?, 90, ?, now())
                ON CONFLICT (metric) DO UPDATE
                  SET value = EXCLUDED.value,
                      sample_size = EXCLUDED.sample_size,
                      percentile = EXCLUDED.percentile,
                      corpus_fingerprint = EXCLUDED.corpus_fingerprint,
                      calibrated_at = now()
                """, metric, value, n, fingerprint);
    }

    /**
     * 当前语料指纹：文档数 + 分块数的组合。
     *
     * <p>用途是发现「语料换了但阈值没重标」：线上定时比对这个指纹，
     * 与标定时记下的不一致就告警，而不是等用户发现答得不对。
     */
    public String currentCorpusFingerprint() {
        try {
            List<String> rows = jdbc.query(
                    "SELECT md5(COUNT(*)::text || '-' || COALESCE(SUM(chunk_count), 0)::text) "
                            + "FROM documents WHERE status = 'DONE'",
                    (rs, i) -> rs.getString(1));
            return rows.isEmpty() ? "unknown" : rows.get(0);
        } catch (Exception e) {
            return "unknown";
        }
    }
}
