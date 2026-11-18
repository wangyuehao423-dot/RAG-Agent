package com.ragagent.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 精排：把（问题，候选块）成对喂给交叉编码器，重新打一个相关性分。
 *
 * <h3>为什么需要这一步</h3>
 * 双塔（向量召回）是「问题和文档各自编码成向量，再算夹角」——两条编码互不知情，
 * 所以它只能判断「两段话像不像」，判断不了「这段能不能回答这个问题」。
 * 典型失效：问「年假多少天」，命中「病假规定：需医院证明，全年不超过 30 天」，
 * 余弦 0.58 很高（都在讲假期制度），但这段答不了年假。
 * 交叉编码器把问题和文档拼成一条序列过模型，能看到「年假 vs 病假」的差别——这是它唯一的价值。
 *
 * <h3>为什么是批量一次调用，不是每条一次</h3>
 * DashScope 的 text-rerank 接口就是 query + documents[] 的形式，一次请求带上全部候选，
 * 返回的 results 里带 index 回指原位置。所以 20 条候选 = 1 次 HTTP 调用。
 * 成本不在网络往返，在算力：每条候选都要过一遍 transformer，延迟随条数线性增长。
 * 这就是候选池只截到 20 条（{@code app.retrieval.candidate-top-k}）的原因——
 * 20 条是「延迟预算」和「把 RRF 排错的那几条捞回来」之间的平衡点。
 *
 * <h3>失败了怎么办</h3>
 * 精排是「排序优化」，不是「链路必需」。超时、限流、解析失败一律返回 null，
 * 由 {@link HybridRetriever} 降级为 RRF 顺序继续走——
 * 少了精排只是答案排序差一点，不能因为一个可选环节让整个问答失败。
 * 超时给 3 秒：实测单次 20 条在几百毫秒内，3 秒已经是很宽的余量，
 * 再长就会把用户可感知的等待拖到不可接受。
 *
 * <h3>边界</h3>
 * <ul>
 *   <li>未配置 api-key 时自动禁用，不报错，直接走降级（本地开发不该因为这个起不来）</li>
 *   <li>返回条数与输入不一致时按失败处理：宁可降级，也不要把分数错位贴到别的块上</li>
 *   <li>它只改顺序，不改 {@code similarity} / {@code lexicalScore}——那两个分仍然是召回时算出来的值，
 *       拒答判据用的还是它们</li>
 * </ul>
 */
@Service
public class RerankService {

    private static final Logger log = LoggerFactory.getLogger(RerankService.class);

    private final RestClient client;
    private final ObjectMapper mapper;
    private final String url;
    private final String model;
    private final boolean enabled;

    public RerankService(ObjectMapper mapper,
                         @Value("${app.rerank.enabled:true}") boolean enabled,
                         @Value("${app.rerank.url}") String url,
                         @Value("${app.rerank.api-key:}") String apiKey,
                         @Value("${app.rerank.model:gte-rerank-v2}") String model,
                         @Value("${app.rerank.timeout-ms:3000}") int timeoutMs) {
        this.mapper = mapper;
        this.url = url;
        this.model = model;
        this.enabled = enabled && apiKey != null && !apiKey.isBlank();

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        this.client = RestClient.builder()
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();

        if (this.enabled) {
            log.info("精排已启用 model={} timeout={}ms", model, timeoutMs);
        } else {
            log.warn("精排未启用（app.rerank.enabled=false 或未配置 DASHSCOPE_API_KEY），检索将直接使用 RRF 顺序");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 对一批候选重新打分。
     *
     * @param query 用户问题（原文，不做改写——精排要看的正是「这个问题」和文档的匹配）
     * @param docs  候选块正文，顺序即 RRF 顺序
     * @return 与 {@code docs} 等长的分数数组，下标与原候选一一对应；任何失败返回 {@code null}
     */
    public double[] rerank(String query, List<String> docs) {
        if (!enabled || docs.isEmpty()) {
            return null;
        }
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("query", query);
        input.put("documents", docs);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("return_documents", false);
        params.put("top_n", docs.size());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", input);
        body.put("parameters", params);

        try {
            String response = client.post()
                    .uri(url)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            JsonNode results = mapper.readTree(response).path("output").path("results");
            if (!results.isArray() || results.size() != docs.size()) {
                log.warn("精排返回条数与输入不一致，降级回 RRF 顺序 input={} output={}",
                        docs.size(), results.isArray() ? results.size() : -1);
                return null;
            }
            double[] scores = new double[docs.size()];
            for (JsonNode r : results) {
                int index = r.path("index").asInt(-1);
                if (index < 0 || index >= scores.length) {
                    log.warn("精排返回下标越界 index={} size={}，降级回 RRF 顺序", index, scores.length);
                    return null;
                }
                scores[index] = r.path("relevance_score").asDouble(0.0);
            }
            return scores;
        } catch (Exception e) {
            log.warn("精排调用失败，降级回 RRF 顺序：{}", e.toString());
            return null;
        }
    }
}
