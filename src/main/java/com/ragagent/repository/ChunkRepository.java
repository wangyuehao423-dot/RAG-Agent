package com.ragagent.repository;

import com.ragagent.retrieval.ScoredChunk;
import com.ragagent.util.ChineseTokenizer;
import com.ragagent.util.ModelCallGuard;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class ChunkRepository {
    private final JdbcTemplate jdbc;
    private final EmbeddingModel embeddingModel;

    public ChunkRepository(JdbcTemplate jdbc, EmbeddingModel embeddingModel) {
        this.jdbc = jdbc;
        this.embeddingModel = embeddingModel;
    }

    public void saveWithVector(String documentId, int chunkIndex, String content, String sourceName, float[] vector) {
        String tokens = ChineseTokenizer.toText(content);
        jdbc.update(
                "INSERT INTO chunks(document_id, chunk_index, content, content_tsv, embedding, source_name) "
                        + "VALUES (?::uuid, ?, ?, to_tsvector('simple', ?), ?::vector, ?)",
                documentId, chunkIndex, content, tokens, vectorToString(vector), sourceName);
    }

    public List<float[]> embedBatch(List<String> texts) {
        return ModelCallGuard.call("embedding", () ->
                embeddingModel
                        .embedAll(texts.stream().map(TextSegment::from).toList())
                        .content().stream()
                        .map(Embedding::vector)
                        .toList());
    }

    /** 向量检索：余弦相似度（1 - 余弦距离）降序 */
    public List<ScoredChunk> vectorSearch(String query, int topK) {
        float[] qv = embed(query);
        String q = vectorToString(qv);
        return jdbc.query(
                "SELECT id, content, source_name, 1 - (embedding <=> ?::vector) AS similarity "
                        + "FROM chunks ORDER BY embedding <=> ?::vector LIMIT ?",
                (rs, i) -> new ScoredChunk(
                        rs.getString("id"),
                        rs.getString("content"),
                        rs.getString("source_name"),
                        rs.getDouble("similarity")),
                q, q, topK);
    }

    private float[] embed(String text) {
        return ModelCallGuard.call("embedding", () ->
                embeddingModel.embed(text).content().vector());
    }

    /** 词法检索：中文 bigram 全文检索 + ts_rank 打分 */
    public List<ScoredChunk> bm25Search(String query, int topK) {
        String tsQuery = ChineseTokenizer.toTsQuery(query);
        if (tsQuery == null || tsQuery.isEmpty()) {
            return List.of();
        }
        return jdbc.query(
                "SELECT id, content, source_name, ts_rank(content_tsv, to_tsquery('simple', ?)) AS score "
                        + "FROM chunks WHERE content_tsv @@ to_tsquery('simple', ?) ORDER BY score DESC LIMIT ?",
                (rs, i) -> new ScoredChunk(
                        rs.getString("id"),
                        rs.getString("content"),
                        rs.getString("source_name"),
                        rs.getDouble("score")),
                tsQuery, tsQuery, topK);
    }

    /** float[] → pgvector 文本格式，如 [0.1,0.2,0.3] */
    private static String vectorToString(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        sb.append(']');
        return sb.toString();
    }
}
