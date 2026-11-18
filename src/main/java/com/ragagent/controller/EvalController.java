package com.ragagent.controller;

import com.ragagent.eval.EvalExporter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 评测接口：导出数据 + 读报告。
 *
 * <p>为什么是「导出」和「报告」两个接口，而不是一个 {@code /run?mode=smoke|export}：
 * 一个接口两用会让调用方必须记住「这次传什么 mode」，而且返回体形状随 mode 变，
 * 前端没法写类型。分开之后职责单一：export 只出数据，report 只读结论。
 *
 * <p>「冒烟层」已经被删掉了——用一句「关键词命中率」当快速验证，测的是字符串在不在，
 * 不是答案对不对。一个测不准的快速版只会给假的安全感。要快就缩小样本量，不降低判据质量。
 */
@RestController
@RequestMapping("/api/eval")
public class EvalController {

    private final EvalExporter exporter;

    public EvalController(EvalExporter exporter) {
        this.exporter = exporter;
    }

    /**
     * 跑一遍用例集并导出四列 JSONL。
     *
     * <p>同步执行、可能十几分钟——这是离线任务，不放在请求链路上等结果才是错的用法。
     * 真实使用建议在后端机器上直接调，或用 curl 后台跑。
     */
    @PostMapping("/export")
    public EvalExporter.ExportResult export() {
        return exporter.export();
    }

    /** 读最近一次 RAGAS 结果，并跟基线做 diff */
    @GetMapping("/report")
    public Map<String, Object> report(@RequestParam(required = false) Long baselineId) {
        return exporter.report(baselineId);
    }

    /**
     * RAGAS 结果回写（由 scripts/ragas_eval.py 调）。
     *
     * <p>为什么让 Python 回写而不是 Java 去读文件：算分在 Python，
     * 结论就该由算分的那一端写进库，避免「文件路径不一致 → Java 读到旧文件」这类静默错。
     */
    @PostMapping("/report")
    public Map<String, Object> writeReport(@RequestBody RagasReportRequest req) {
        Long id = exporter.recordRagasRun(req.caseCount(), req.faithfulness(), req.answerRelevancy(),
                req.contextPrecision(), req.contextRecall(), req.note());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("note", "已写入 eval_runs，可用 GET /api/eval/report 看本次与基线的 diff");
        return body;
    }

    /** RAGAS 四指标 + 用例数。字段名对齐 RAGAS 输出，减少映射代码 */
    public record RagasReportRequest(
            int caseCount,
            Double faithfulness,
            Double answerRelevancy,
            Double contextPrecision,
            Double contextRecall,
            String note
    ) {
    }
}
