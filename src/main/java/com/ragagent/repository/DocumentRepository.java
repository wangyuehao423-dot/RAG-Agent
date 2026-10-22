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
        return jdbc.queryForList(
                "SELECT id, name, file_type, chunk_count, status, error_msg, created_at "
                        + "FROM documents ORDER BY created_at DESC");
    }

    public boolean existsById(UUID id) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM documents WHERE id = ?", Integer.class, id);
        return n != null && n > 0;
    }

    public Map<String, Object> findById(UUID id) {
        return jdbc.queryForMap(
                "SELECT id, name, file_type, chunk_count, status, error_msg, created_at "
                        + "FROM documents WHERE id = ?", id);
    }

    public List<Map<String, Object>> findChunks(UUID documentId) {
        return jdbc.queryForList(
                "SELECT chunk_index, content FROM chunks WHERE document_id = ? ORDER BY chunk_index", documentId);
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
