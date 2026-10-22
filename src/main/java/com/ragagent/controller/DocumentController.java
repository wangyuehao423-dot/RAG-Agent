package com.ragagent.controller;

import com.ragagent.exception.ModelRequestException;
import com.ragagent.exception.ModelUnavailableException;
import com.ragagent.ingestion.IngestionService;
import com.ragagent.repository.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {
    private static final Logger log = LoggerFactory.getLogger(DocumentController.class);

    private final IngestionService ingestion;
    private final DocumentRepository documentRepository;

    public DocumentController(IngestionService ingestion, DocumentRepository documentRepository) {
        this.ingestion = ingestion;
        this.documentRepository = documentRepository;
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
        return doc;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "上传文件为空");
        }
        try {
            String docId = ingestion.ingest(file.getOriginalFilename(), file.getBytes());
            return Map.of("documentId", docId, "status", "DONE");
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
