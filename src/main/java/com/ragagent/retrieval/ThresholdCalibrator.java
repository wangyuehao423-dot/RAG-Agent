package com.ragagent.retrieval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 阈值的离线标定：用一批「已知无关」的查询跑全库，拿背景分布的高分位作为门槛。
 *
 * <h3>标定口径</h3>
 * 对每条无关查询，取两条通道各自的 top1 分数（向量余弦相似度、词法 ts_rank）。
 * 这些分数构成「背景分布」——它们代表「语料里最像的东西有多像」。
 * 取 P90 作为阈值：只有明显高于背景噪声的检索才算有把握。
 *
 * <h3>为什么不用「相关问题 vs 无关问题」的实测间隔中点</h3>
 * 那个做法要先有一批带标准答案的问题，而且挑哪个点仍是人工决策；
 * 更致命的是它绑定语料——换一版知识库，两个分布都变了，得重新挑一次。
 * 分位数是纯统计量：脚本重跑，阈值自动跟着走。
 *
 * <h3>三条边界（必须自己知道）</h3>
 * <ol>
 *   <li>样本少时 P90 不稳：少于 20 条负例时结果只能当参考，接口会把这个事实返回给调用方</li>
 *   <li>阈值只做粗筛：飘 0.05 不会直接出事，第二道（模型表态）兜着</li>
 *   <li>标定依赖负例质量：如果知识库里恰好有「红烧肉做法」，那条就不算负例</li>
 * </ol>
 */
@Service
public class ThresholdCalibrator {

    private static final Logger log = LoggerFactory.getLogger(ThresholdCalibrator.class);

    /**
     * 默认背景查询集：与「企业制度 / 员工手册」类知识库无关的通用问题。
     *
     * <p>为什么内置一组而不是要求调用方传：标定这件事如果不能一条命令跑完，
     * 就没有人会定期跑。默认集覆盖天气、饮食、娱乐、医疗、交通、编程等常见领域，
     * 足够撑起一个稳定的背景分布；需要更精确时可以传入自定义负例覆盖它。
     */
    private static final List<String> DEFAULT_PROBES = List.of(
            "今天天气怎么样",
            "明天股票会涨吗",
            "红烧肉怎么做",
            "最近有什么好看的电影",
            "帮我写一首关于春天的诗",
            "飞机为什么能飞起来",
            "世界杯冠军是哪支球队",
            "怎么才能快速减肥",
            "感冒了应该吃什么药",
            "汽车多久保养一次",
            "Python 和 Java 哪个更好",
            "房价明年还会涨吗",
            "太阳系一共有几颗行星",
            "如何申请护照",
            "猫咪为什么喜欢纸箱",
            "怎样提高英语口语",
            "咖啡一天喝几杯合适",
            "手机电池怎么保养",
            "马拉松全程多少公里",
            "怎么挑选一台笔记本电脑"
    );

    private final HybridRetriever retriever;
    private final ThresholdService thresholds;

    public ThresholdCalibrator(HybridRetriever retriever, ThresholdService thresholds) {
        this.retriever = retriever;
        this.thresholds = thresholds;
    }

    /** 标定结果：把分布也一并返回，方便判断这次的 P90 是不是可信 */
    public record CalibrationResult(
            double similarity,
            double lexical,
            int sampleSize,
            double similarityP50,
            double similarityP90,
            double similarityMax,
            String corpusFingerprint,
            boolean sampleEnough,
            String note
    ) {
    }

    /** 用默认负例标定 */
    public CalibrationResult calibrate() {
        return calibrate(DEFAULT_PROBES);
    }

    /**
     * 用指定负例标定并写回。
     *
     * @param probes 已知与知识库无关的查询；为空则退回默认集
     */
    public CalibrationResult calibrate(List<String> probes) {
        List<String> queries = (probes == null || probes.isEmpty()) ? DEFAULT_PROBES : probes;
        List<Double> similarities = new ArrayList<>();
        List<Double> lexicalScores = new ArrayList<>();

        for (String q : queries) {
            List<RetrievalResult> results = retriever.search(q);
            if (results.isEmpty()) {
                // 全库都没命中，这条对背景分布没有贡献（记 0 会把 P90 拉低）
                continue;
            }
            RetrievalResult top = results.get(0);
            similarities.add(top.similarity());
            lexicalScores.add(top.lexicalScore());
        }

        if (similarities.isEmpty()) {
            // 知识库是空的：标定没有意义，保持现有阈值不动
            log.warn("标定失败：知识库为空或所有负例都无命中，阈值保持不变");
            return new CalibrationResult(thresholds.similarity(), thresholds.lexical(), 0,
                    0, 0, 0, thresholds.currentCorpusFingerprint(), false,
                    "知识库为空，标定未执行");
        }

        double simP90 = percentile(similarities, 0.90);
        double lexP90 = percentile(lexicalScores, 0.90);
        String fingerprint = thresholds.currentCorpusFingerprint();
        thresholds.apply(simP90, lexP90, similarities.size(), fingerprint);

        boolean enough = similarities.size() >= 20;
        String note = enough
                ? "样本充足，P90 可作为判据落点"
                : "样本不足 20 条，P90 仅作参考；建议扩充负例集后重标";

        return new CalibrationResult(
                simP90, lexP90, similarities.size(),
                percentile(similarities, 0.50), simP90, percentile(similarities, 1.0),
                fingerprint, enough, note);
    }

    /** 线性插值分位数：样本量小的时候比「取第 ceil(p*n) 个」更稳 */
    static double percentile(List<Double> values, double p) {
        if (values.isEmpty()) {
            return 0.0;
        }
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        if (sorted.size() == 1) {
            return sorted.get(0);
        }
        double pos = p * (sorted.size() - 1);
        int lo = (int) Math.floor(pos);
        int hi = (int) Math.ceil(pos);
        if (lo == hi) {
            return sorted.get(lo);
        }
        double weight = pos - lo;
        return sorted.get(lo) * (1 - weight) + sorted.get(hi) * weight;
    }
}
