package com.ragagent.exception;

/**
 * 上游模型服务「拒绝」了本次请求（不可重试）。
 *
 * 与 ModelUnavailableException 的分工：
 *     ModelUnavailableException -> 依赖抖动，可重试，最终映射 503
 *     ModelRequestException     -> 请求被拒，重试无意义，最终映射 502
 *
 * 什么时候抛？（对应上游返回的「确定性失败」4xx）
 *     401 / 403   API key 无效、无权限、配额被禁用
 *     400         请求体不合法（模型名写错、参数越界）
 *     404         模型 / 路径不存在
 * 这些错误重试多少次结果都一样，属于我们的配置问题，
 * 必须立即失败并明确告知，而不是白白重试几轮后再报「稍后重试」——
 * 那会让调用方以为「过一会儿再来」有用，实际上是永远等不到。
 */
public class ModelRequestException extends RuntimeException {

    /**
     * 稳定错误码（"AI_KEY_INVALID" / "AI_BAD_REQUEST" / "AI_MODEL_NOT_FOUND" /
     * "AI_REQUEST_REJECTED"），由全局处理器原样写进响应体，
     * 说明「到底要改哪儿」，而不只是「不可重试」。
     */
    private final String errorCode;

    public ModelRequestException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
