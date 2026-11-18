package com.ragagent.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 原始文件落盘。
 *
 * <p>为什么要落盘而不是把字节留在内存里：入库已经改成异步（接口立刻回 202），
 * 上传请求的线程在投递消息后就结束了，byte[] 随之被回收。消费者是另一个线程、
 * 甚至可能是另一个实例，它必须能从磁盘上重新读到这份文件。
 *
 * <p>文件名用 documentId 而不是原始文件名：原始文件名可能重名、可能带路径分隔符
 * 或控制字符（上传方可以随便填），直接拼路径会有目录穿越风险。
 */
@Component
public class DocumentFileStore {

    private static final Logger log = LoggerFactory.getLogger(DocumentFileStore.class);

    private final Path root;

    public DocumentFileStore(@Value("${app.storage.upload-dir:data/uploads}") String uploadDir) {
        this.root = Paths.get(uploadDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建上传目录: " + root, e);
        }
        log.info("上传文件目录: {}", root);
    }

    /** 边写边落盘，返回绝对路径 */
    public String save(String documentId, byte[] content) {
        Path target = root.resolve(documentId);
        try {
            Files.write(target, content);
            return target.toString();
        } catch (IOException e) {
            throw new IllegalStateException("文件落盘失败: " + documentId, e);
        }
    }

    public byte[] read(String storedPath) {
        try {
            return Files.readAllBytes(Paths.get(storedPath));
        } catch (IOException e) {
            throw new IllegalStateException("读取上传文件失败: " + storedPath, e);
        }
    }

    /** 入库成功或永久失败后清理，避免磁盘无限增长 */
    public void delete(String storedPath) {
        try {
            Files.deleteIfExists(Paths.get(storedPath));
        } catch (IOException e) {
            // 清理失败不影响业务正确性（只是磁盘占用），记一条日志即可，不要冒泡打断主流程
            log.warn("上传文件清理失败: {}", storedPath, e);
        }
    }
}
