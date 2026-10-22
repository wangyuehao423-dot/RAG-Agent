package com.ragagent.exception;

/**
 * 上游模型服务（对话 / 向量化）不可用（可重试）。
 *
 * 为什么要单独定义一个异常类型？
 * 因为「依赖服务抖动」和「我们自己的代码有 bug」是两回事：
 *     依赖抖动  -> 语义上是 503 Service Unavailable，
 *                  临时性、可重试，应该告诉用户「稍后再试」
 *     代码 bug  -> 才是 500，这是我们的责任，要改代码
 * 如果两者都归成 500，运维告警无法分级，
 * 前端也拿不到「可以重试」的明确信号。
 */
public class ModelUnavailableException extends RuntimeException {

    /**
     * 稳定错误码（如 "AI_RATE_LIMITED" / "AI_UPSTREAM_ERROR" / "AI_UNAVAILABLE"），
     * 随异常一路传到全局处理器，由它写进响应体。
     * 调用方据此做条件分支，不必解析中文文案。
     */
    private final String errorCode;

    public ModelUnavailableException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
