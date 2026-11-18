package com.ragagent.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 段级状态仓储：承载「大文件分段入库」的进度、幂等与对账三件事。
 *
 * <p>为什么粒度要降到「段」而不是整篇文档：
 * 一份 100MB 的文档会拆成几十段，一段失败只需要重投这一段，
 * 已经成功的段不重跑。文档级的重做单元太粗，一个错字就要跑完整篇。
 *
 * <p>三样东西落在段上：
 * <ul>
 *   <li>进度：{@code segment_done / segment_total}，前端能显示 3/8</li>
 *   <li>幂等：段表唯一键 {@code (document_id, seq)}，消费前查状态，DONE 直接跳过</li>
 *   <li>对账：各段 chunk_count 之和 == chunks 实际行数，粒度从「文档」降到「段」</li>
 * </ul>
 */
@Repository
public class SegmentRepository {

    private final JdbcTemplate jdbc;

    public SegmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 一段在全文中的位置：从第 chunkStart 块到第 chunkEnd 块（左闭右开） */
    public record Slice(int seq, int chunkStart, int chunkEnd) {
        public int chunkCount() {
            return chunkEnd - chunkStart;
        }
    }

    /**
     * 登记全部段并写回总数；<b>已经登记过就原样返回库里的段</b>。
     *
     * <p>为什么不能每次都重新登记：MQ 重投时会再走一遍流程，
     * 如果这里把 {@code segment_total} 和 {@code segment_done} 重新写一遍，
     * 已完成的段进度会被清零，收敛条件永远等不到。
     * 所以「段一旦登记就不可变」，重投只复用，不重建。
     */
    public List<Slice> declareSegments(String documentId, List<Slice> slices) {
        List<Slice> existing = loadSlices(documentId);
        if (!existing.isEmpty()) {
            return existing;
        }
        jdbc.update("UPDATE documents SET segment_total = ?, segment_done = 0 WHERE id = ?::uuid",
                slices.size(), documentId);
        for (Slice s : slices) {
            // ON CONFLICT DO NOTHING：并发两个消费者同时登记也不会报错，
            // 也保住了 (document_id, seq) 这条唯一键的幂等语义。
            jdbc.update("INSERT INTO document_segments(document_id, seq, chunk_start, chunk_end, chunk_count) "
                            + "VALUES (?::uuid, ?, ?, ?, ?) ON CONFLICT (document_id, seq) DO NOTHING",
                    documentId, s.seq(), s.chunkStart(), s.chunkEnd(), s.chunkCount());
        }
        return slices;
    }

    /** 读取已登记的段（按 seq 升序） */
    public List<Slice> loadSlices(String documentId) {
        return jdbc.query(
                "SELECT seq, chunk_start, chunk_end FROM document_segments "
                        + "WHERE document_id = ?::uuid ORDER BY seq",
                (rs, i) -> new Slice(rs.getInt("seq"), rs.getInt("chunk_start"), rs.getInt("chunk_end")),
                documentId);
    }

    /** 这一段是否已经做完（重投时用来跳过已完成段） */
    public boolean isDone(String documentId, int seq) {
        List<String> status = jdbc.queryForList(
                "SELECT status FROM document_segments WHERE document_id = ?::uuid AND seq = ?",
                String.class, documentId, seq);
        return !status.isEmpty() && "DONE".equals(status.get(0));
    }

    /**
     * 把一段标记完成，并原子推进 {@code segment_done}；返回「是否已全部完成」。
     *
     * <p><b>收敛为什么只有一次能成立</b>：靠的是自增的原子性，不是靠「最后一条消息是谁」。
     * 两个消费者同时完成第 7、8 段（总数 8），自增后一个拿到 8、一个拿到 7，
     * 只有拿到 8 的那个把文档置 DONE，另一个什么也不做。
     *
     * <p><b>重投幂等</b>：段已经是 DONE 时 {@code UPDATE} 影响 0 行，不再自增，
     * 所以同一段被消费十次，{@code segment_done} 也只会加一次。
     *
     * @return true 表示本篇最后一段刚好在这里完成，文档已置 DONE
     */
    public boolean markDoneAndAdvance(String documentId, int seq) {
        int updated = jdbc.update(
                "UPDATE document_segments SET status = 'DONE', updated_at = now() "
                        + "WHERE document_id = ?::uuid AND seq = ? AND status <> 'DONE'",
                documentId, seq);
        if (updated == 0) {
            // 这段之前已经完成过（MQ 重投），不要重复自增，直接返回当前是否全完成
            return allSegmentsDone(documentId);
        }
        List<Integer> done = jdbc.query(
                "UPDATE documents SET segment_done = segment_done + 1 WHERE id = ?::uuid RETURNING segment_done",
                (rs, i) -> rs.getInt(1), documentId);
        List<Integer> total = jdbc.query(
                "SELECT segment_total FROM documents WHERE id = ?::uuid",
                (rs, i) -> rs.getInt(1), documentId);
        if (done.isEmpty() || total.isEmpty()) {
            return false;
        }
        boolean finished = done.get(0) >= total.get(0);
        if (finished) {
            // chunk_count 用段表求和反算，而不是在内存里累加：
            // 合并并发多消费者的写入结果，只有从库里读才是准的。
            jdbc.update("UPDATE documents SET status = 'DONE', chunk_count = "
                            + "(SELECT COALESCE(SUM(chunk_count), 0) FROM document_segments WHERE document_id = ?::uuid) "
                            + "WHERE id = ?::uuid",
                    documentId, documentId);
        }
        return finished;
    }

    /** 段永久失败：只标这一段，其他已完成段保留 */
    public void markFailed(String documentId, int seq, String error) {
        jdbc.update("UPDATE document_segments SET status = 'FAILED', error_msg = ?, updated_at = now() "
                        + "WHERE document_id = ?::uuid AND seq = ?",
                error, documentId, seq);
    }

    /** 重投前把这一段重置为待处理，让 markDoneAndAdvance 能重新自增 */
    public void resetToPending(String documentId, int seq) {
        jdbc.update("UPDATE document_segments SET status = 'PENDING', updated_at = now() "
                        + "WHERE document_id = ?::uuid AND seq = ? AND status = 'FAILED'",
                documentId, seq);
    }

    public boolean allSegmentsDone(String documentId) {
        List<Integer> remaining = jdbc.query(
                "SELECT COUNT(*) FROM document_segments WHERE document_id = ?::uuid AND status <> 'DONE'",
                (rs, i) -> rs.getInt(1), documentId);
        return !remaining.isEmpty() && remaining.get(0) == 0;
    }

    public List<java.util.Map<String, Object>> findByDocument(String documentId) {
        return jdbc.queryForList(
                "SELECT seq, chunk_start, chunk_end, chunk_count, status, error_msg "
                        + "FROM document_segments WHERE document_id = ?::uuid ORDER BY seq", documentId);
    }
}
