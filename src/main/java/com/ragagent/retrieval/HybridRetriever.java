package com.ragagent.retrieval;

import com.ragagent.repository.ChunkRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class HybridRetriever {
    private final ChunkRepository chunkRepository;
    private final int vectorTopK;
    private final int bm25TopK;
    private final int fusionTopK;
    private final int rrfK;

    public HybridRetriever(ChunkRepository chunkRepository,
                           @Value("${app.retrieval.vector-top-k:10}") int vectorTopK,
                           @Value("${app.retrieval.bm25-top-k:10}") int bm25TopK,
                           @Value("${app.retrieval.fusion-top-k:5}") int fusionTopK,
                           @Value("${app.retrieval.rrf-k:60}") int rrfK) {
        this.chunkRepository = chunkRepository;
        this.vectorTopK = vectorTopK;
        this.bm25TopK = bm25TopK;
        this.fusionTopK = fusionTopK;
        this.rrfK = rrfK;
    }
    private void addChannel(List<ScoredChunk> list, Map<String, Double> rrfScore, Map<String, ScoredChunk> byId) {
        for (int i = 0; i < list.size(); i++) {
            ScoredChunk c = list.get(i);
            rrfScore.merge(c.id(), 1.0 / (rrfK + i + 1), Double::sum);
            byId.putIfAbsent(c.id(), c);
        }
    }
    public List<RetrievalResult> search(String query)
    {
        List<ScoredChunk> vector = chunkRepository.vectorSearch(query, vectorTopK);
        List<ScoredChunk> bm25 = chunkRepository.bm25Search(query, bm25TopK);
        Map<String, Double> similarityById = new LinkedHashMap<>();
        for(ScoredChunk c : vector)
        {
            similarityById.putIfAbsent(c.id(), c.score());
        }
        Map<String, Double> lexicalById = new LinkedHashMap<>();
        for(ScoredChunk c : bm25)
        {
            lexicalById.putIfAbsent(c.id(), c.score());
        }
        Map<String, Double> rrfScore = new LinkedHashMap<>();
        Map<String, ScoredChunk> byId = new LinkedHashMap<>();
        addChannel(vector, rrfScore, byId);
        addChannel(bm25, rrfScore, byId);
        return rrfScore.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(fusionTopK)
                .map(e -> {
                    ScoredChunk c = byId.get(e.getKey());
                    return new RetrievalResult(
                            c.id(),
                            c.content(),
                            c.sourceName(),
                            similarityById.getOrDefault(c.id(), 0.0),
                            lexicalById.getOrDefault(c.id(), 0.0),
                            e.getValue());
                })
                .toList();
    }    
}
