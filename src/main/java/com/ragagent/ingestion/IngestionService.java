package com.ragagent.ingestion;

import com.ragagent.repository.ChunkRepository;
import com.ragagent.repository.DocumentRepository;
import com.ragagent.repository.SegmentRepository;
import com.ragagent.repository.SegmentRepository.Slice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * 文档入库：提交（同步，毫秒级）与处理（异步，消费端）两段。
 *
 * <h3>为什么要拆成两段</h3>
 * 一个 0.9MB 的 PDF 会切出 535 块，每块要打一次 embedding 接口，串行要 60 秒以上，
 * 撑不住任何网关超时。所以接口只做三件事：算哈希、落盘、写一行 documents（处理中）、
 * 投一条消息进队列，然后立刻回 202。真正的活由消费者异步做。
 *
 * <h3>两阶段（段内）为什么不能合成一个事务</h3>
 * 阶段一解析、分块、向量化（调网络接口）；阶段二一个短事务写库。
 * 网络调用绝不能进事务：一次 embedding 调用几百毫秒到几秒，
 * 放进事务就是把数据库连接和行锁按住那么久，并发一上来连接池直接打满。
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    /** text-embedding-v3 单次请求的条数上限 */
    private static final int EMBED_BATCH_SIZE = 25;

    private final DocumentParserService parser;
    private final ChunkingService chunking;
    private final ChunkRepository chunkRepository;
    private final DocumentRepository documentRepository;
    private final SegmentRepository segmentRepository;
    private final DocumentFileStore fileStore;
    private final IngestionProducer producer;
    private final TransactionTemplate txTemplate;
    private final int segmentMaxChunks;

    public IngestionService(DocumentParserService parser,
                            ChunkingService chunking,
                            ChunkRepository chunkRepository,
                            DocumentRepository documentRepository,
                            SegmentRepository segmentRepository,
                            DocumentFileStore fileStore,
                            IngestionProducer producer,
                            TransactionTemplate txTemplate,
                            @Value("${app.ingestion.segment-max-chunks:100}") int segmentMaxChunks) {
        this.parser = parser;
        this.chunking = chunking;
        this.chunkRepository = chunkRepository;
        this.documentRepository = documentRepository;
        this.segmentRepository = segmentRepository;
        this.fileStore = fileStore;
        this.producer = producer;
        this.txTemplate = txTemplate;
        this.segmentMaxChunks = segmentMaxChunks;
    }

    /** 提交结果：deduped=true 表示命中已有内容、没有重新投递 */
    public record SubmitResult(String documentId, String status, boolean deduped) {
    }

    /* ==================== 第一段：提交（同步、毫秒级） ==================== */

    /**
     * 上传入口：算哈希 → 判重 → 落盘 → 写 documents（处理中）→ 投消息 → 返回。
     *
     * <p>去重是两道一起上：先查一次是为了快（命中就秒传，不解析、不向量化、不存第二份文件），
     * 数据库唯一索引是为了对（两个请求同时传同一文件会同时查不到，靠撞键兜住）。
     * 两个不是替代关系，少一个都漏。
     */
    public SubmitResult submit(String filename, byte[] content) {
        // 类型校验放在投递之前：不支持的类型直接 400。
        // 不放在消费者的原因——异步之后，一个 .exe 会先拿到 202「已受理」，
        // 白写一行 documents、白投一条消息，几秒后才变 FAILED；
        // 而扩展名是提交阶段就能判断的，没必要拖到消费端才失败。
        if (!DocumentParserService.supports(filename)) {
            String ext = DocumentParserService.extension(filename);
            throw new IllegalArgumentException(
                    ext.isEmpty() ? "文件缺少扩展名，无法判断类型" : "不支持的文件类型: " + ext);
        }

        String hash = sha256(content);
        DocumentRepository.ExistingDoc existing = documentRepository.findByContentHash(hash);

        if (existing != null) {
            if ("DONE".equals(existing.status()) || "PROCESSING".equals(existing.status())) {
                log.info("命中内容哈希，秒传 documentId={} status={}", existing.id(), existing.status());
                return new SubmitResult(existing.id(), existing.status(), true);
            }
            // 命中 FAILED 不能秒传：秒传的前提是那份文档确实入库成功了，
            // 否则用户拿到的是一个永远失败的文档。复用同一条记录重跑——
            // content_hash 上有唯一索引，不能另插一条。
            String path = fileStore.save(existing.id(), content);
            documentRepository.resetToProcessing(existing.id());
            producer.publishPlan(new IngestionTask(existing.id(), filename, path, 0));
            log.info("命中失败记录，重投入库 documentId={}", existing.id());
            return new SubmitResult(existing.id(), "PROCESSING", false);
        }

        String documentId = UUID.randomUUID().toString();
        String path = fileStore.save(documentId, content);
        try {
            documentRepository.insert(documentId, filename,
                    DocumentParserService.extension(filename), hash, 0, "PROCESSING");
        } catch (DuplicateKeyException e) {
            // 并发上传同一份文件：另一个请求抢先插入了。删掉刚落的这份文件，
            // 回查已有记录返回——不重复投递，因为对方已经投过了。
            fileStore.delete(path);
            DocumentRepository.ExistingDoc raced = documentRepository.findByContentHash(hash);
            if (raced != null) {
                return new SubmitResult(raced.id(), raced.status(), true);
            }
            throw e;
        }

        producer.publishPlan(new IngestionTask(documentId, filename, path, 0));
        return new SubmitResult(documentId, "PROCESSING", false);
    }

    /* ==================== 第二段之一：文档级（解析 + 拆段） ==================== */

    /**
     * 解析、分块、拆段、登记，然后一段投一条消息。
     *
     * <p>这一段只做「算」不做「写」：解析和分块都在内存里，唯一的库操作是登记段表。
     * 登记必须完成才能投段消息，因为消费者的收敛判断依赖 segment_total。
     */
    public void plan(IngestionTask task) {
        byte[] content = fileStore.read(task.storedPath());

        String text;
        try {
            text = parser.parse(task.filename(), content);
        } catch (RuntimeException e) {
            // 文件损坏、编码不对、格式不支持——重投一百次结果一样，必须判成不可重试
            throw new PermanentIngestionException("文档解析失败: " + e.getMessage(), e);
        }
        List<ChunkPiece> pieces = chunking.chunk(text);
        if (pieces.isEmpty()) {
            throw new PermanentIngestionException("文档内容为空或无法解析出有效文本");
        }
        // 原文先落库：引用来源要能跳回原文件的那一行，靠的就是它。
        // 放在这里而不是 ingestSegment，是因为 plan 成功后原文件就被删了，
        // 而 ingestSegment 是另一条消息、可能是另一个实例在处理。
        documentRepository.saveRawText(task.documentId(), text);

        List<Slice> slices = segmentRepository.declareSegments(task.documentId(), split(pieces.size()));
        for (Slice s : slices) {
            if (segmentRepository.isDone(task.documentId(), s.seq())) {
                continue;   // 重投时跳过已完成的段
            }
            int end = Math.min(s.chunkEnd(), pieces.size());
            if (s.chunkStart() >= end) {
                continue;
            }
            List<ChunkPiece> sub = pieces.subList(s.chunkStart(), end);
            producer.publishSegment(new SegmentTask(
                    task.documentId(), task.filename(), s.seq(), s.chunkStart(),
                    sub.stream().map(ChunkPiece::text).toList(),
                    sub.stream().map(p -> new SegmentTask.LineSpan(p.startLine(), p.endLine())).toList(),
                    0));
        }
        // 段消息已经把文本带走了，原始文件不再需要。plan 成功才删：
        // plan 失败重投时还要靠它重新解析。
        fileStore.delete(task.storedPath());
        log.info("拆段完成 documentId={} chunks={} segments={} rawChars={}",
                task.documentId(), pieces.size(), slices.size(), text.length());
    }

    /* ==================== 第二段之二：段级（向量化 + 写库） ==================== */

    /**
     * 处理一个段：向量化（事务外）→ 一个短事务写 chunks + 段置 DONE + 计数自增。
     *
     * <p>为什么向量化在事务外：它在调网络，几百毫秒到几秒。放进事务里，
     * 数据库连接会被一条慢接口占住，并发一上来连接池就满了。
     */
    public void ingestSegment(SegmentTask task) {
        // 幂等：MQ 至少投递一次，同一段可能被投两次。
        // 已经 DONE 的段直接返回，不重复向量化、不重复写库。
        if (segmentRepository.isDone(task.documentId(), task.seq())) {
            log.info("段已完成，跳过 documentId={} seq={}", task.documentId(), task.seq());
            return;
        }

        List<String> chunks = task.chunks();
        List<float[]> vectors = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i += EMBED_BATCH_SIZE) {
            vectors.addAll(chunkRepository.embedBatch(
                    chunks.subList(i, Math.min(i + EMBED_BATCH_SIZE, chunks.size()))));
        }

        // ---- 阶段二：一个短事务，只写 ----
        // chunk_index 用 chunkStart + i，保证全文连续：段是重做单元，不是编号单元。
        txTemplate.executeWithoutResult(status -> {
            if (segmentRepository.isDone(task.documentId(), task.seq())) {
                return;   // 并发两个消费者都通过了上面的检查，事务内再挡一次
            }
            for (int i = 0; i < chunks.size(); i++) {
                // 行号跟着消息走，与 chunks 同序；历史消息没有这个字段时按 0 写，
                // 前端据此提示「该文档入库时未保留原文定位」
                int startLine = 0;
                int endLine = 0;
                if (i < task.lineSpans().size()) {
                    startLine = task.lineSpans().get(i).startLine();
                    endLine = task.lineSpans().get(i).endLine();
                }
                chunkRepository.saveWithVector(task.documentId(), task.chunkStart() + i,
                        chunks.get(i), task.filename(), vectors.get(i), startLine, endLine);
            }
            segmentRepository.markDoneAndAdvance(task.documentId(), task.seq());
        });
    }

    /** 永久失败：只标这一段，其他已完成段保留，整篇置 FAILED（残缺但看起来正常比明确失败更糟） */
    public void markSegmentFailed(SegmentTask task, String reason) {
        txTemplate.executeWithoutResult(status -> {
            segmentRepository.markFailed(task.documentId(), task.seq(), reason);
            documentRepository.markFailed(task.documentId(),
                    "第 " + (task.seq() + 1) + " 段入库失败：" + reason);
        });
    }

    /** 整篇在拆段阶段就不可重试失败 */
    public void markDocumentFailed(IngestionTask task, String reason) {
        documentRepository.markFailed(task.documentId(), reason);
        documentRepository.resetProgress(task.documentId());
        fileStore.delete(task.storedPath());
    }

    /* ==================== 工具 ==================== */

    /** 按段大小切分：[0,100) [100,200) ... 最后一段可能不满 */
    private List<Slice> split(int totalChunks) {
        List<Slice> slices = new ArrayList<>();
        int seq = 0;
        for (int start = 0; start < totalChunks; start += segmentMaxChunks) {
            slices.add(new Slice(seq++, start, Math.min(start + segmentMaxChunks, totalChunks)));
        }
        return slices;
    }

    /** 文件内容 SHA-256：算在原始字节流上，不能算在解析后的文本上 */
    private static String sha256(byte[] content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", e);
        }
    }
}
