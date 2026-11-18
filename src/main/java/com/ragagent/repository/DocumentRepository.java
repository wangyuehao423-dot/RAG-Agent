package com.ragagent.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class DocumentRepository {
    private final JdbcTemplate jdbc;
    public DocumentRepository(JdbcTemplate jdbc)
    {
        this.jdbc = jdbc;
    }
    public void insert(String id, String name, String fileType, String contentHash, int chunkCount, String status)
    {
        jdbc.update("INSERT INTO documents(id, name, file_type, content_hash, chunk_count, status) "
                        + "VALUES (?::uuid, ?, ?, ?, ?, ?)",
                id, name, fileType, contentHash, chunkCount, status);
    }

    public List<Map<String, Object>> findAll()
    {
        // has_raw 让前端知道这份文档能不能「点来源跳原文」——老数据入库时没存原文，
        // 列出来但点不动，不如提前告诉用户原因。
        return jdbc.queryForList(
                "SELECT id, name, file_type, chunk_count, status, error_msg, created_at, "
                        + "(raw_text IS NOT NULL) AS has_raw "
                        + "FROM documents ORDER BY created_at DESC");
    }

    public boolean existsById(UUID id) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM documents WHERE id = ?", Integer.class, id);
        return n != null && n > 0;
    }

    public Map<String, Object> findById(UUID id) {
        return jdbc.queryForMap(
                "SELECT id, name, file_type, chunk_count, status, error_msg, created_at, "
                        + "(raw_text IS NOT NULL) AS has_raw "
                        + "FROM documents WHERE id = ?", id);
    }

    /**
     * 保存解析后的全文。引用来源要「跳回原文那一行」，必须有原文可跳。
     *
     * <p>放在拆段阶段写：那时原文件还没删；等到 ingestSegment 再写就晚了——
     * 段消息可能被另一个实例消费，它手上只有分块文本，没有整篇原文。
     */
    public void saveRawText(String documentId, String text) {
        jdbc.update("UPDATE documents SET raw_text = ? WHERE id = ?::uuid", text, documentId);
    }

    /** 取原文用于前端展示；没存原文返回 null（老数据） */
    public String findRawText(UUID id) {
        List<String> rows = jdbc.queryForList(
                "SELECT raw_text FROM documents WHERE id = ?", String.class, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Map<String, Object>> findChunks(UUID documentId) {
        return jdbc.queryForList(
                "SELECT chunk_index, content, start_line, end_line FROM chunks "
                        + "WHERE document_id = ? ORDER BY chunk_index", documentId);
    }

    public int countChunks(UUID documentId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM chunks WHERE document_id = ?", Integer.class, documentId);
        return n == null ? 0 : n;
    }

    public String findIdByContentHash(String contentHash) {
        List<String> ids = jdbc.queryForList(
                "SELECT id::text FROM documents WHERE content_hash = ?", String.class, contentHash);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** 按内容哈希查已有文档（含状态）：秒传要用状态判断「能不能秒传」 */
    public ExistingDoc findByContentHash(String contentHash) {
        List<ExistingDoc> docs = jdbc.query(
                "SELECT id::text, status FROM documents WHERE content_hash = ? ORDER BY created_at LIMIT 1",
                (rs, i) -> new ExistingDoc(rs.getString(1), rs.getString(2)),
                contentHash);
        return docs.isEmpty() ? null : docs.get(0);
    }

    public record ExistingDoc(String id, String status) {
    }

    /** 入库失败：写状态 + 原因，让用户看到「为什么没进去」，而不是一句未知错误 */
    public void markFailed(String documentId, String error) {
        jdbc.update("UPDATE documents SET status = 'FAILED', error_msg = ? WHERE id = ?::uuid",
                error, documentId);
    }

    /** 命中 FAILED 记录后重跑：重置状态与错误信息，段表在登记阶段会复用已有记录 */
    public void resetToProcessing(String documentId) {
        jdbc.update("UPDATE documents SET status = 'PROCESSING', error_msg = NULL WHERE id = ?::uuid",
                documentId);
    }

    /** 提交阶段失败（还没登记段）时把总数置为 0，避免前端一直显示 0/1 */
    public void resetProgress(String documentId) {
        jdbc.update("UPDATE documents SET segment_total = 0, segment_done = 0 WHERE id = ?::uuid", documentId);
    }
    /**
     * 删除一条文档。
     *
     * 只删 documents：它的分块由 chunks.document_id 上的
     * 外键 + ON DELETE CASCADE 自动清理。
     * 之所以不再手写 DELETE FROM chunks，是因为两条语句一旦
     * 不在同一事务里、中间失败，就会留下孤儿分块 ——
     * 它们仍会被检索命中，成为删不掉的幽灵来源。
     * 把一致性交给数据库，比在应用层维护语句顺序可靠。
     */
    public void deleteById(UUID id) {
        jdbc.update("DELETE FROM documents WHERE id = ?", id);
    }
}
