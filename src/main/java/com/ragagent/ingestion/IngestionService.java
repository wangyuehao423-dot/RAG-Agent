package com.ragagent.ingestion;

import com.ragagent.repository.ChunkRepository;
import com.ragagent.repository.DocumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class IngestionService {
    private static final int EMBED_BATCH_SIZE = 25;

    private final DocumentParserService parser;
    private final ChunkingService chunking;
    private final ChunkRepository chunkRepository;
    private final DocumentRepository documentRepository;
    private final TransactionTemplate txTemplate;

    public IngestionService(DocumentParserService parser,
                            ChunkingService chunking,
                            ChunkRepository chunkRepository,
                            DocumentRepository documentRepository,
                            TransactionTemplate txTemplate) {
        this.parser = parser;
        this.chunking = chunking;
        this.chunkRepository = chunkRepository;
        this.documentRepository = documentRepository;
        this.txTemplate = txTemplate;
    }

    /** 文件内容 SHA-256，用于重复上传去重 */
    private static String sha256(byte[] content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", e);
        }
    }

    private String persist(String filename, String hash, List<String> chunks, List<float[]> vectors) {
        String documentId = UUID.randomUUID().toString();
        String ext = DocumentParserService.extension(filename);

        documentRepository.insert(documentId, filename, ext, hash, chunks.size(), "DONE");

        for (int i = 0; i < chunks.size(); i++) {
            chunkRepository.saveWithVector(documentId, i, chunks.get(i), filename, vectors.get(i));
        }
        return documentId;
    }

    public String ingest(String filename, byte[] content) {
        String hash = sha256(content);
        String existingId = documentRepository.findIdByContentHash(hash);
        if (existingId != null) {
            return existingId;
        }
        String text = parser.parse(filename, content);
        List<String> chunks = chunking.chunk(text);
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("文档内容为空或无法解析出有效文本");
        }
        List<float[]> vectors = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i += EMBED_BATCH_SIZE) {
            List<String> batch = chunks.subList(i, Math.min(i + EMBED_BATCH_SIZE, chunks.size()));
            vectors.addAll(chunkRepository.embedBatch(batch));
        }
        // ---- 阶段二：短事务写库 ----
        // 用 TransactionTemplate 而不是给本方法加 @Transactional：
        // 同类内部方法互调不走 Spring 代理，@Transactional 会静默失效。
        return txTemplate.execute(status -> persist(filename, hash, chunks, vectors));
    }
}
