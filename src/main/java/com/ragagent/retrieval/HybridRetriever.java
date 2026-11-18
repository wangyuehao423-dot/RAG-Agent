package com.ragagent.retrieval;

import com.ragagent.repository.ChunkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索主链路：两路召回 → RRF 融合 → 精排 → 截断。
 *
 * <h3>为什么是 30 / 60 / 20 / 5 这四个数</h3>
 * <ol>
 *   <li><b>各召回 30</b>：单路召回有系统性盲区——向量通道对编号/型号这类无实义的字符串没区分度，
 *       词法通道对同义改写（「报销流程」vs「费用核销步骤」）零命中。候选池太小，
 *       另一路本来能救回来的那条根本没进池子，融合就失去了意义。</li>
 *   <li><b>融合后 60 → 取 20</b>：两路会有重叠，去重后最多 60 条。全送精排不行——
 *       精排是逐条过 transformer，延迟随条数线性涨，60 条要三倍时间；
 *       而 RRF 排到 20 名以后的，进最终 top5 的概率已经很低，不值得为它付算力。</li>
 *   <li><b>精排后取 5</b>：5 条是喂给大模型的上下文预算——再多 token 涨、噪声也涨，
 *       实测再往里塞对答案质量没有正向收益。</li>
 * </ol>
 *
 * <h3>精排失败为什么可以直接降级</h3>
 * 精排是「排序优化」，不是「链路必需」。它失败时按 RRF 顺序取前 5 继续走，
 * 用户拿到的是「排序稍差」的答案，而不是一个 500。
 * 判据（拒答阈值）也不依赖它：那个用的是召回时算出来的 similarity / lexicalScore。
 *
 * <h3>一个容易忽略的连带影响</h3>
 * 精排会改变「第 1 名是谁」，而拒答判据看的是第 1 名的分数。
 * 这不会让系统失准，因为阈值是 {@link ThresholdCalibrator} 用同一条 {@link #search} 链路
 * 跑负例标定出来的——链路变了，重跑一次标定，P90 会跟着落到新位置上。
 * 反过来说：<b>改了召回条数、开了精排、换了向量模型，都必须重标一次阈值</b>，
 * 否则阈值还是按旧分布算的，会静默偏掉。
 */
@Service
public class HybridRetriever {

    private static final Logger log = LoggerFactory.getLogger(HybridRetriever.class);

    private final ChunkRepository chunkRepository;
    private final RerankService rerankService;
    /** 每路召回的条数 */
    private final int vectorTopK;
    private final int bm25TopK;
    /** 融合后送进精排的候选数 */
    private final int candidateTopK;
    /** 精排后最终留给大模型的条数 */
    private final int fusionTopK;
    private final int rrfK;

    public HybridRetriever(ChunkRepository chunkRepository,
                           RerankService rerankService,
                           @Value("${app.retrieval.vector-top-k:30}") int vectorTopK,
                           @Value("${app.retrieval.bm25-top-k:30}") int bm25TopK,
                           @Value("${app.retrieval.candidate-top-k:20}") int candidateTopK,
                           @Value("${app.retrieval.fusion-top-k:5}") int fusionTopK,
                           @Value("${app.retrieval.rrf-k:60}") int rrfK) {
        this.chunkRepository = chunkRepository;
        this.rerankService = rerankService;
        this.vectorTopK = vectorTopK;
        this.bm25TopK = bm25TopK;
        this.candidateTopK = Math.max(candidateTopK, fusionTopK);
        this.fusionTopK = fusionTopK;
        this.rrfK = rrfK;
    }

    /**
     * 两路召回的 RRF 累加：每路的第 i 名贡献 1/(k + i + 1)。
     *
     * <p>用排名而不是分数累加，是因为余弦相似度和 ts_rank 量纲差两个数量级（0.62 vs 0.06），
     * 直接相加等于让向量通道单方面决定结果。RRF 只看名次，天然免疫量纲问题。
     */
    private void addChannel(List<ScoredChunk> list, Map<String, Double> rrfScore, Map<String, ScoredChunk> byId) {
        for (int i = 0; i < list.size(); i++) {
            ScoredChunk c = list.get(i);
            rrfScore.merge(c.id(), 1.0 / (rrfK + i + 1), Double::sum);
            byId.putIfAbsent(c.id(), c);
        }
    }

    public List<RetrievalResult> search(String query) {
        List<ScoredChunk> vector = chunkRepository.vectorSearch(query, vectorTopK);
        List<ScoredChunk> bm25 = chunkRepository.bm25Search(query, bm25TopK);

        // 两路各自的分要按 id 留住：融合后还需要它们做拒答判据，不能丢
        Map<String, Double> similarityById = new LinkedHashMap<>();
        for (ScoredChunk c : vector) {
            similarityById.putIfAbsent(c.id(), c.score());
        }
        Map<String, Double> lexicalById = new LinkedHashMap<>();
        for (ScoredChunk c : bm25) {
            lexicalById.putIfAbsent(c.id(), c.score());
        }

        Map<String, Double> rrfScore = new LinkedHashMap<>();
        Map<String, ScoredChunk> byId = new LinkedHashMap<>();
        addChannel(vector, rrfScore, byId);
        addChannel(bm25, rrfScore, byId);

        List<RetrievalResult> candidates = rrfScore.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(candidateTopK)
                .map(e -> {
                    ScoredChunk c = byId.get(e.getKey());
                    return new RetrievalResult(
                            c.id(),
                            c.content(),
                            c.sourceName(),
                            similarityById.getOrDefault(c.id(), 0.0),
                            lexicalById.getOrDefault(c.id(), 0.0),
                            e.getValue(),
                            c.documentId(),
                            c.chunkIndex(),
                            c.startLine(),
                            c.endLine());
                })
                .toList();

        return rerankAndCut(query, candidates);
    }

    /** 精排 + 截断；精排不可用或失败时按 RRF 顺序截断。 */
    private List<RetrievalResult> rerankAndCut(String query, List<RetrievalResult> candidates) {
        if (candidates.size() <= fusionTopK) {
            // 候选本来就凑不满最终条数，重排没有意义（也省一次远程调用）
            return candidates;
        }

        double[] scores = rerankService.rerank(
                query, candidates.stream().map(RetrievalResult::content).toList());

        if (scores == null) {
            log.debug("精排不可用，按 RRF 顺序返回 top{}", fusionTopK);
            return candidates.subList(0, fusionTopK);
        }

        List<RetrievalResult> reranked = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            reranked.add(candidates.get(i).withRerankScore(scores[i]));
        }
        reranked.sort(Comparator.comparingDouble(RetrievalResult::rerankScore).reversed());
        return reranked.subList(0, fusionTopK);
    }
}
