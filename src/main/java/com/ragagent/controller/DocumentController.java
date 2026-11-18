package com.ragagent.controller;

import com.ragagent.exception.ModelRequestException;
import com.ragagent.exception.ModelUnavailableException;
import com.ragagent.ingestion.IngestionService;
import com.ragagent.repository.DocumentRepository;
import com.ragagent.repository.SegmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {
    private static final Logger log = LoggerFactory.getLogger(DocumentController.class);

    private final IngestionService ingestion;
    private final DocumentRepository documentRepository;
    private final SegmentRepository segmentRepository;

    public DocumentController(IngestionService ingestion,
                              DocumentRepository documentRepository,
                              SegmentRepository segmentRepository) {
        this.ingestion = ingestion;
        this.documentRepository = documentRepository;
        this.segmentRepository = segmentRepository;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return documentRepository.findAll();
    }

    @GetMapping("/{id}/content")
    public Map<String, Object> content(@PathVariable String id) {
        UUID uuid;
        try {
            uuid = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法的文档 ID 格式: " + id);
        }
        if (!documentRepository.existsById(uuid)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在: " + id);
        }
        Map<String, Object> doc = documentRepository.findById(uuid);
        doc.put("chunks", documentRepository.findChunks(uuid));
        // 段进度：处理中的文档前端能直接显示「第 3/8 段」，而不是只有一个「处理中」。
        // 这就是三态状态机在异步化之后的真正职责——不再是「对账的依据」，而是「进度的可观测」。
        doc.put("segments", segmentRepository.findByDocument(id));
        return doc;
    }

    /**
     * 取解析后的原文全文，供「点引用来源跳转到原文那一行」用。
     *
     * <p>为什么不复用 {@code /content}：那里返回的是分块，块里带标题路径前缀、丢了空行，
     * 行号和真实文件对不上；而且一个文档几百块，前端只需要看被引用的那几行，
     * 拉全部块再拼是浪费。原文单独一个接口，前端按需取、取一次缓存住。
     *
     * <p>老数据没有 raw_text，这里返回 200 + hasRaw=false + 说明，
     * 而不是 404——「文档存在但没存原文」和「文档不存在」是两件事，
     * 用同一个状态码会让前端没法给出准确提示。
     */
    @GetMapping("/{id}/raw")
    public Map<String, Object> raw(@PathVariable String id) {
        UUID uuid;
        try {
            uuid = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法的文档 ID 格式: " + id);
        }
        if (!documentRepository.existsById(uuid)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在: " + id);
        }
        Map<String, Object> doc = documentRepository.findById(uuid);
        String raw = documentRepository.findRawText(uuid);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("documentId", id);
        body.put("name", doc.get("name"));
        body.put("hasRaw", raw != null);
        body.put("rawText", raw);
        body.put("lineCount", raw == null ? 0 : raw.split("\\R", -1).length);
        if (raw == null) {
            body.put("note", "该文档入库时未保留原文（早期版本只存分块），重新上传同一文件即可获得行级定位。");
        }
        return body;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "上传文件为空");
        }
        try {
            IngestionService.SubmitResult result =
                    ingestion.submit(file.getOriginalFilename(), file.getBytes());

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("documentId", result.documentId());
            body.put("status", result.status());
            body.put("deduped", result.deduped());
            body.put("message", result.deduped()
                    ? "内容已存在，直接复用已有文档"
                    : "已受理，正在后台解析入库");

            // 秒传命中已完成的文档 → 200（事情已经做完了）；
            // 其余都是「已受理、还没做完」→ 202，前端据此知道要轮询状态。
            HttpStatus status = ("DONE".equals(result.status()))
                    ? HttpStatus.OK : HttpStatus.ACCEPTED;
            return ResponseEntity.status(status).body(body);
        } catch (ResponseStatusException e) {
            throw e;                       // 业务异常原样透传
        } catch (ModelUnavailableException e) {
            throw e;                       // 依赖故障交给全局处理器 → 503（可重试）
        } catch (ModelRequestException e) {
            throw e;                       // 请求被拒交给全局处理器 → 502（不可重试）
        } catch (IllegalArgumentException e) {
            // 不支持的文件类型、内容为空/无法解析等，属于调用方问题 → 400
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        } catch (Exception e) {
            // 关键：不要把 e.getMessage() 拼进对外文案！
            // 它可能带着完整 SQL、表名、字段名或框架内部堆栈，
            // 会绕过 GlobalExceptionHandler 专门写的「不泄露 SQL」分支。
            // 这里只写服务端日志，对外统一给一句通用提示。
            log.error("文档入库失败: {}", file.getOriginalFilename(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "文档入库失败，请稍后重试");
        }
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        UUID uuid;
        try {
            uuid = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法的文档 ID 格式: " + id);
        }

        if (!documentRepository.existsById(uuid)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在: " + id);
        }
        int chunkCount = documentRepository.countChunks(uuid);

        // 只删一条：chunks 由外键 ON DELETE CASCADE 自动清理。
        // 不要再手写 DELETE FROM chunks —— 两条语句一旦不在同一个事务里，
        // 中途失败就会留下孤儿分块（它们仍会被检索命中，成为删不掉的幽灵引用）。
        // 一致性交给数据库，比在应用层维护语句顺序可靠得多。
        documentRepository.deleteById(uuid);
        return Map.of("deleted", id, "deletedChunks", chunkCount);
    }
}
