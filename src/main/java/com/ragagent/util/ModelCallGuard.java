package com.ragagent.util;

import com.ragagent.exception.ModelRequestException;
import com.ragagent.exception.ModelUnavailableException;
import dev.langchain4j.exception.HttpException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 远程模型调用的防护壳：有限重试 + 指数退避 + 故障分类。
 *
 * 远程调用失败的原因分成两类，处理方式完全不同：
 *   可重试（依赖抖动）：网络中断、连接超时、429 限流、5xx
 *           -> 等一会儿再来一次，往往就好了
 *           -> 最多尝试 3 次（即重试 2 次，退避 200ms / 400ms），
 *              仍失败则抛 ModelUnavailableException -> 映射 503
 *   不可重试（请求被拒）：401/403（key 无效）、
 *             400（请求本身有问题）、404
 *           -> 重试多少次都一样，属于配置问题
 *           -> 【一次都不重试】，立即抛 ModelRequestException -> 映射 502
 *
 * 关键：不可重试的失败必须立刻退出，绝不能进重试循环。
 * 否则一个 401（key 写错）也要白等 200ms + 400ms，
 * 最后还被包成「服务暂时不可用，请稍后重试」——
 * 既浪费请求，又给调用方一个完全错误的指引：
 * 它需要的是改配置，不是等待，再等一万次也不会成功。
 *
 * 这里做两层判断，可靠程度不同，必须分清楚：
 *
 *   第 1 层「可不可重试」——优先靠异常类型，其次靠 HTTP 状态码：
 *       langchain4j 1.20.2 已把上游错误映射成类型化异常，且底层状态码
 *       保留在 HttpException.statusCode() 里（upstreamStatus 就是去异常链里找它），
 *       —— 拿到状态码后按码判断（见 isNonRetryableStatus）。
 *
 *   第 2 层「具体是哪种错」——同样靠 HTTP 状态码细化，
 *     属于尽力而为：类型化异常的 message 只有 JSON body，状态码在
 *     HttpException.statusCode()；解析不到就退回粗粒度兜底 code，【绝不因此失败】。
 *     为什么要细化？401（key 错）和 400（参数错）都不可重试，
 *     但调用方要做的动作完全不同：一个去改 key，一个去改参数。
 *     只给一个「不可重试」的粗码，等于把两件事混成一件。
 *
 * 为什么退避而不是立刻重试？
 * 因为限流（429）的本质是「你太快了」，
 * 立刻重试只会加重限流。
 * 退避 200ms、400ms 让服务端喘口气，
 * 这是各家 SDK 的通用做法。
 */
public final class ModelCallGuard {

    private static final Logger log = LoggerFactory.getLogger(ModelCallGuard.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final long BASE_BACKOFF_MS = 200;

    /** 上游 HTTP 状态码通常位于 LangChain4j 异常消息的开头，形如 "401 - {...}" */
    private static final Pattern STATUS_PREFIX = Pattern.compile("^\\s*(\\d{3})(?!\\d)");

    /* ---------- 不可重试（4xx）：原因明确，给出可区分的 code ---------- */

    /** 401 / 403：API key 无效、无权限、配额被禁用 */
    public static final String CODE_KEY_INVALID = "AI_KEY_INVALID";
    /** 400：请求体不合法（模型名写错、参数越界） */
    public static final String CODE_BAD_REQUEST = "AI_BAD_REQUEST";
    /** 404：模型 / 路径不存在 */
    public static final String CODE_MODEL_NOT_FOUND = "AI_MODEL_NOT_FOUND";
    /** 其它 4xx：状态码解析不到时的兜底 */
    public static final String CODE_REQUEST_REJECTED = "AI_REQUEST_REJECTED";

    /* ---------- 可重试（429 / 5xx / 网络抖动）：重试耗尽后给出原因 ---------- */

    /** 429：被限流 */
    public static final String CODE_RATE_LIMITED = "AI_RATE_LIMITED";
    /** 5xx：上游服务端错误 */
    public static final String CODE_UPSTREAM_ERROR = "AI_UPSTREAM_ERROR";
    /** 其它抖动（连接超时、网络中断等，拿不到状态码）时的兜底 */
    public static final String CODE_UNAVAILABLE = "AI_UNAVAILABLE";

    private ModelCallGuard() {
    }

    /**
     * 执行一次远程调用：
     * 不可重试的失败 -> 立即抛 ModelRequestException（不进循环）；
     * 可重试的失败   -> 按指数退避重试，仍失败抛 ModelUnavailableException。
     *
     * @param what   用于日志与异常文案的调用描述，例如 "embedding" / "chat"
     * @param action 真正的远程调用
     */
    public static <T> T call(String what, Supplier<T> action) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return action.get();
            } catch (RuntimeException e) {
                // 先分类：不可重试（401/403/400/404）立刻失败，
                // 省掉没有意义的等待与重试，也不把它伪装成「稍后重试」。
                int status = upstreamStatus(e);
                if (isNonRetryableStatus(status)) {
                    String code = nonRetryableCode(status);
                    log.warn("{} 调用失败（不可重试, code={}）: {}", what, code, e.getMessage());
                    throw new ModelRequestException(code, what + " 请求被上游拒绝（不可重试）", e);
                }
                last = e;
                log.warn("{} 第 {} 次调用失败: {}", what, attempt, e.getMessage());
                if (attempt < MAX_ATTEMPTS) {
                    sleep(BASE_BACKOFF_MS * (1L << (attempt - 1)));   // 200ms、400ms
                }
            }
        }
        String code = retryableCode(upstreamStatus(last));
        log.warn("{} 重试耗尽（code={}）: {}", what, code, last == null ? "" : last.getMessage());
        throw new ModelUnavailableException(code, what + " 连续 " + MAX_ATTEMPTS + " 次调用失败", last);
    }

    /**
     * 判断状态码是否为「不可重试」。
     *
     * 注意：这个方法对应 Spring AI 版的 isNonRetryable(e)。
     * Spring AI 通过异常「类型」（NonTransientAiException）判断；
     * langchain4j 1.20.2 也有类型化异常（NonRetriableException / RetriableException），
     * 但底层状态码统一挂在 HttpException.statusCode() 上，所以这里仍「按码判断」，
     * 由 upstreamStatus 去异常链里取码。
     * 401/403/400/404 属于确定性的配置/参数问题，重试多少次都一样，直接判死；
     * 429/5xx/网络超时等其余状态一律按「可重试」处理。
     */
    private static boolean isNonRetryableStatus(int status) {
        return status == 401 || status == 403 || status == 400 || status == 404;
    }

    /**
     * 尽力从异常链里提取上游 HTTP 状态码。
     *
     * <p>langchain4j 1.20.2 的上游错误已经映射成类型化异常，且底层 HTTP 状态码
     * 保留在 {@link dev.langchain4j.exception.HttpException#statusCode()} 里——
     * 类型化异常（AuthenticationException / RateLimitException 等）的 message
     * 只有上游返回的 JSON body，不含状态码前缀，所以只靠抠消息会永远解析不到。
     * 这里「类型优先、消息兜底」：
     *   1) 异常链里找到 HttpException -> 直接取 statusCode()；
     *   2) 找不到再退回「消息开头形如 401 - ...」的正则。
     * 解析不到返回 -1，由调用方退回兜底 code —— 这个方法【不抛异常】：
     * 否则上游改一次文案，就会把一次本来可恢复的调用直接变成崩溃。
     */
    private static int upstreamStatus(RuntimeException e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof HttpException he) {
                return he.statusCode();
            }
            String msg = t.getMessage();
            if (msg != null) {
                Matcher m = STATUS_PREFIX.matcher(msg);
                if (m.find()) {
                    return Integer.parseInt(m.group(1));
                }
            }
            t = t.getCause();
        }
        return -1;
    }

    private static String nonRetryableCode(int status) {
        return switch (status) {
            case 401, 403 -> CODE_KEY_INVALID;
            case 400 -> CODE_BAD_REQUEST;
            case 404 -> CODE_MODEL_NOT_FOUND;
            default -> CODE_REQUEST_REJECTED;
        };
    }

    private static String retryableCode(int status) {
        if (status == 429) {
            return CODE_RATE_LIMITED;
        }
        if (status >= 500 && status <= 599) {
            return CODE_UPSTREAM_ERROR;
        }
        return CODE_UNAVAILABLE;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new ModelUnavailableException(CODE_UNAVAILABLE, "重试等待被中断", ie);
        }
    }
}
