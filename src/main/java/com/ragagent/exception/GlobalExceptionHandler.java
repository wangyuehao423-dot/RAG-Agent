package com.ragagent.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常处理：把各类异常统一映射成 JSON 错误体。
 *   - 一般错误：{ "error": "..." }
 *   - 模型类错误：额外带 "code"，让调用方做机器化分支，不必解析中文文案：
 *       可重试（503）：AI_RATE_LIMITED / AI_UPSTREAM_ERROR / AI_UNAVAILABLE
 *       不可重试（502）：AI_KEY_INVALID / AI_BAD_REQUEST / AI_MODEL_NOT_FOUND / AI_REQUEST_REJECTED
 * 注意：本类不做任何解析 —— 状态码按「异常类型」选，code 直接取异常自带的；
 * code 的具体取值是在 ModelCallGuard 里定的（那里才拿得到 Spring AI 的异常消息）。
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 第 1 类：业务显式抛出的状态异常 —— 原样保留状态码与提示。 */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleStatus(ResponseStatusException e) {
        String msg = e.getReason() == null ? "请求处理失败" : e.getReason();
        return ResponseEntity.status(e.getStatusCode()).body(errorBody(msg));
    }

    /** 第 2 类：模型 / 向量化不可用（可重试）-> 503，并给出 Retry-After。 */
    @ExceptionHandler(ModelUnavailableException.class)
    public ResponseEntity<Map<String, String>> handleModelUnavailable(ModelUnavailableException e) {
        log.warn("上游模型服务不可用（code={}）: {}", e.getErrorCode(), e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "5")
                .body(errorBody(e.getErrorCode(), "模型服务暂时不可用，请稍后重试"));
    }

    /** 第 3 类：模型请求被上游拒绝（不可重试）-> 502，明确告知重试无效。 */
    @ExceptionHandler(ModelRequestException.class)
    public ResponseEntity<Map<String, String>> handleModelRequest(ModelRequestException e) {
        log.error("上游拒绝模型请求（不可重试, code={}）: {}", e.getErrorCode(), e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(errorBody(e.getErrorCode(), "模型请求被上游拒绝，请检查模型配置（重试无效）"));
    }

    /** 第 4 类：数据库访问异常 -> 通用提示，详情只写服务端日志。 */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, String>> handleDataAccess(DataAccessException e) {
        log.error("数据库访问异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(errorBody("服务内部错误，请稍后重试"));
    }

    /** 第 5 类：上传超过 multipart 上限 -> 413。由父类 ResponseEntityExceptionHandler
     * 已声明的 @ExceptionHandler(MaxUploadSizeExceededException) 路由到 handleExceptionInternal，
     * 本类不再重复声明（重复声明会与父类映射歧义，启动即失败）。 */

    /** 第 6 类：兜底，其余未预期异常 -> 通用提示。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handle(Exception e) {
        log.error("未处理异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(errorBody("服务内部错误，请稍后重试"));
    }

    /**
     * 第 7 类（关键）：覆盖父类的统一出口。
     *
     * 父类已经处理了框架语义异常并给出正确状态码，
     * 但默认返回的是 RFC 7807 的 ProblemDetail 结构，
     * 与项目其它接口的 {"error": "..."} 不一致。
     * 这里覆盖统一出口，把 body 也换成同样的形状。
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        String msg = messageFor(status == null ? HttpStatus.INTERNAL_SERVER_ERROR : status);
        if (status != null && status.is5xxServerError()) {
            log.error("请求处理异常: {}", ex.getMessage(), ex);
        }
        return ResponseEntity.status(statusCode).headers(headers).body(errorBody(msg));
    }

    private String messageFor(HttpStatus status) {
        return switch (status) {
            case NOT_FOUND -> "请求的资源不存在";
            case METHOD_NOT_ALLOWED -> "请求方法不被支持";
            case UNSUPPORTED_MEDIA_TYPE -> "请求的 Content-Type 不被支持";
            case BAD_REQUEST -> "请求参数不合法或请求体格式错误";
            case PAYLOAD_TOO_LARGE -> "上传文件过大，请拆分后重试";
            case SERVICE_UNAVAILABLE -> "服务暂时不可用，请稍后重试";
            default -> "请求处理失败";
        };
    }

    /** 固定键顺序的错误体：{ "error": "..." } */
    private static Map<String, String> errorBody(String error) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", error);
        return body;
    }

    /** 固定键顺序的错误体：{ "code": "...", "error": "..." } */
    private static Map<String, String> errorBody(String code, String error) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("error", error);
        return body;
    }
}
