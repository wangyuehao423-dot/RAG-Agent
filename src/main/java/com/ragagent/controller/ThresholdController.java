package com.ragagent.controller;

import com.ragagent.retrieval.RetrievalDriftMonitor;
import com.ragagent.retrieval.ThresholdCalibrator;
import com.ragagent.retrieval.ThresholdService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 拒答阈值与检索漂移接口。
 *
 * <p>为什么阈值要有自己的接口：它是「可以重算的统计量」，不是「改一次配置文件」。
 * 换语料、换 embedding 模型之后，正确动作是调一次标定，不是去 yml 里手改一个数。
 */
@RestController
@RequestMapping("/api/retrieval")
public class ThresholdController {

    private final ThresholdCalibrator calibrator;
    private final ThresholdService thresholds;
    private final RetrievalDriftMonitor driftMonitor;

    public ThresholdController(ThresholdCalibrator calibrator,
                               ThresholdService thresholds,
                               RetrievalDriftMonitor driftMonitor) {
        this.calibrator = calibrator;
        this.thresholds = thresholds;
        this.driftMonitor = driftMonitor;
    }

    /**
     * 重算阈值：用一批「已知无关」查询跑全库，取背景分布 P90。
     *
     * @param req 可选自定义负例；不传就用内置的 20 条通用无关问题
     */
    @PostMapping("/calibrate")
    public ThresholdCalibrator.CalibrationResult calibrate(@RequestBody(required = false) CalibrateRequest req) {
        List<String> probes = req == null ? null : req.probes();
        return calibrator.calibrate(probes);
    }

    /** 当前生效的阈值 + 最近一次的标定口径 */
    @GetMapping("/threshold")
    public Map<String, Object> current() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("similarity", thresholds.similarity());
        body.put("lexical", thresholds.lexical());
        body.put("sampleSize", thresholds.sampleSize());
        body.put("corpusFingerprint", thresholds.corpusFingerprint());
        body.put("note", "阈值是背景分布（已知无关查询）的 P90 在当前语料上的落点，不是固定绝对数。"
                + "换语料或换向量模型后应重跑 POST /api/retrieval/calibrate。");
        return body;
    }

    /** 漂移监控：最近 500 次检索的拒答率与分数分布 */
    @GetMapping("/drift")
    public RetrievalDriftMonitor.DriftStats drift() {
        return driftMonitor.stats();
    }

    public record CalibrateRequest(List<String> probes) {
    }
}
