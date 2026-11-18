package com.ragagent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.agent.ReactAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评测的「出数据」环节：把用例跑成 RAGAS 要的四列 JSONL。
 *
 * <h3>为什么 Java 只负责导出、不负责算分</h3>
 * RAGAS 的指标实现里有一堆非平凡细节（claim 拆分、NLI 判定、embedding 对齐），
 * 在 Java 里重写一遍等于自己给自己发合格证——面试官问「你怎么保证你的实现和别人一致」，
 * 答不出来。所以这里的边界很清楚：Java 出数据（它最懂自己的检索和生成链路），
 * Python 算分（用现成框架），结果回写表，Java 只负责展示和 diff。
 *
 * <h3>为什么不塞进请求链路</h3>
 * 187 条用例每条 4~8 次模型调用，十几分钟。放在请求链路里会把接口拖死，
 * 也会让「评测」变成一件不敢跑的事。它是离线任务，独立触发、独立落盘。
 *
 * <h3>四列缺一列的后果</h3>
 * question 缺了不知道在评什么；answer 缺了无法算忠实度；contexts 缺了无法算上下文精确率；
 * ground_truth 缺了无法算上下文召回率。四个指标正好各依赖一部分，缺哪列哪个指标就废。
 */
@Service
public class EvalExporter {

    private static final Logger log = LoggerFactory.getLogger(EvalExporter.class);

    private final JdbcTemplate jdbc;
    private final ReactAgent agent;
    private final ObjectMapper mapper;
    private final Path exportDir;

    public EvalExporter(JdbcTemplate jdbc,
                        ReactAgent agent,
                        ObjectMapper mapper,
                        @Value("${app.eval.export-dir:data/eval}") String exportDir) {
        this.jdbc = jdbc;
        this.agent = agent;
        this.mapper = mapper;
        this.exportDir = Paths.get(exportDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.exportDir);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建评测导出目录: " + this.exportDir, e);
        }
    }

    public record ExportResult(
            String filename,
            String path,
            int total,
            int exported,
            int refused,
            int failed,
            Long runId,
            String note
    ) {
    }

    /**
     * 跑一遍全部用例并落成 JSONL。
     *
     * <p><b>拒答样本不进数据集</b>：拒答回答里没有事实主张，faithfulness 会算成 NaN
     * 或直接报错，把整个批次的分数带偏。所以它们被单独计数，用来算「漏拒率 / 误拒率」，
     * 不参与四指标。这是 RAGAS 用法里最容易踩的坑。
     */
    public ExportResult export() {
        List<Map<String, Object>> cases = jdbc.queryForList(
                "SELECT id, question, expected FROM eval_cases ORDER BY id");
        if (cases.isEmpty()) {
            return new ExportResult(null, null, 0, 0, 0, 0, null, "评测集为空，请先往 eval_cases 里灌用例");
        }

        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String filename = "eval-" + stamp + ".jsonl";
        Path file = exportDir.resolve(filename);

        int exported = 0;
        int refused = 0;
        int failed = 0;
        List<String> failures = new ArrayList<>();

        try (BufferedWriter writer = Files.newBufferedWriter(file)) {
            for (Map<String, Object> c : cases) {
                String question = String.valueOf(c.get("question"));
                String groundTruth = c.get("expected") == null ? "" : String.valueOf(c.get("expected"));
                try {
                    ReactAgent.EvalSample sample = agent.answerForEval(question);
                    if (sample.refused()) {
                        refused++;
                        continue;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("question", question);
                    row.put("answer", sample.answer());
                    row.put("contexts", sample.contexts());
                    row.put("ground_truth", groundTruth);
                    writer.write(mapper.writeValueAsString(row));
                    writer.newLine();
                    exported++;
                } catch (Exception e) {
                    // 单条失败不静默：计数 + 记日志，最后在返回值里报出来。
                    // 静默跳过会让「187 条怎么只算了 150 条」变成一个查不出来的谜。
                    failed++;
                    failures.add(question);
                    log.warn("评测用例执行失败: {}", question, e);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("写评测文件失败: " + file, e);
        }

        String note = "total=" + cases.size() + " exported=" + exported
                + " refused=" + refused + " failed=" + failed
                + (failures.isEmpty() ? "" : " 失败用例=" + failures);

        Long runId = jdbc.query(
                "INSERT INTO eval_runs(mode, case_count, note) VALUES ('export', ?, ?) RETURNING id",
                (rs, i) -> rs.getLong(1), exported, note).stream().findFirst().orElse(null);

        log.info("评测数据已导出 {} ({})", file, note);
        return new ExportResult(filename, file.toString(), cases.size(), exported, refused, failed, runId, note);
    }

    /**
     * 读最近一次 RAGAS 结果，并与它的上一条做 diff。
     *
     * <p>为什么跟基线比而不是跟绝对值比：「0.87 的忠实度算好吗」没有答案，
     * 0.87 比上次的 0.84 好才是可判断的结论。
     */
    public Map<String, Object> report(Long baselineId) {
        List<Map<String, Object>> ragasRuns = jdbc.queryForList(
                "SELECT id, case_count, faithfulness, answer_relevancy, context_precision, "
                        + "context_recall, note, created_at FROM eval_runs "
                        + "WHERE mode = 'ragas' ORDER BY id DESC LIMIT 2");

        Map<String, Object> body = new LinkedHashMap<>();
        if (ragasRuns.isEmpty()) {
            body.put("note", "还没有 RAGAS 运行记录。先跑 scripts/ragas_eval.py 回写结果。");
            body.put("cases", jdbc.queryForObject("SELECT COUNT(*) FROM eval_cases", Integer.class));
            return body;
        }

        Map<String, Object> latest = ragasRuns.get(0);
        body.put("latest", latest);

        Map<String, Object> previous = null;
        if (baselineId != null) {
            previous = jdbc.queryForList(
                    "SELECT * FROM eval_runs WHERE id = ?", baselineId).stream().findFirst().orElse(null);
        } else if (ragasRuns.size() > 1) {
            previous = ragasRuns.get(1);
        }

        if (previous != null) {
            Map<String, Object> diff = new LinkedHashMap<>();
            for (String k : List.of("faithfulness", "answer_relevancy", "context_precision", "context_recall")) {
                diff.put(k, delta(latest.get(k), previous.get(k)));
            }
            body.put("baseline", previous);
            body.put("diff", diff);
            body.put("hint", "diff 是「本次 - 基线」。正数变好；四项里 context_precision 通常最低，"
                    + "因为 top-5 里必然带噪音。");
        } else {
            body.put("note", "这是第一条 RAGAS 记录，没有基线可比。它的作用是从下次开始当参照。");
        }
        return body;
    }

    /** 回写 RAGAS 结果（由 scripts/ragas_eval.py 调用） */
    public Long recordRagasRun(int caseCount, Double faithfulness, Double answerRelevancy,
                               Double contextPrecision, Double contextRecall, String note) {
        return jdbc.query("""
                        INSERT INTO eval_runs(mode, case_count, faithfulness, answer_relevancy,
                                              context_precision, context_recall, note)
                        VALUES ('ragas', ?, ?, ?, ?, ?, ?) RETURNING id
                        """,
                (rs, i) -> rs.getLong(1),
                caseCount, faithfulness, answerRelevancy, contextPrecision, contextRecall, note)
                .stream().findFirst().orElse(null);
    }

    private static Double delta(Object current, Object previous) {
        if (current == null || previous == null) {
            return null;
        }
        return Math.round((((Number) current).doubleValue() - ((Number) previous).doubleValue()) * 1000d) / 1000d;
    }
}
