package com.xuzhenwei.agent.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 错误分类器 — v2.3新增
 *
 * <p>将异常分为三类，前端据此显示不同样式的错误提示：
 * <ul>
 *   <li>USER_ERROR — 用户输入问题（空内容、不支持格式、超限）</li>
 *   <li>AI_ERROR — AI服务异常（API超时、模型不可用、token耗尽）</li>
 *   <li>SYSTEM_ERROR — 系统内部错误（DB连接、OOM、未知异常）</li>
 * </ul>
 */
@Component
public class ErrorClassifier {

    private static final Logger log = LoggerFactory.getLogger(ErrorClassifier.class);

    public enum ErrorType {
        USER_ERROR("用户操作问题", "⚠️"),
        AI_ERROR("AI服务异常", "🤖"),
        SYSTEM_ERROR("系统内部错误", "🔧");

        private final String label;
        private final String icon;

        ErrorType(String label, String icon) {
            this.label = label;
            this.icon = icon;
        }

        public String getLabel() { return label; }
        public String getIcon() { return icon; }
    }

    public record ClassifiedError(ErrorType type, String userFriendlyMessage, String technicalDetail) {}

    /**
     * 分类异常
     */
    public ClassifiedError classify(Throwable e) {
        if (e == null) {
            return new ClassifiedError(ErrorType.SYSTEM_ERROR, "未知错误，请重试", "null exception");
        }

        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        String name = e.getClass().getSimpleName();

        // === AI服务异常 ===
        if (name.contains("HttpClientError") || name.contains("HttpServerError")
                || msg.contains("429") || msg.contains("rate limit")
                || msg.contains("401") || msg.contains("unauthorized")
                || msg.contains("403") || msg.contains("forbidden")
                || msg.contains("500") || msg.contains("internal server error")
                || msg.contains("503") || msg.contains("service unavailable")
                || msg.contains("timeout") || msg.contains("timed out")
                || msg.contains("connection refused") || msg.contains("connect timeout")
                || msg.contains("model") && msg.contains("not found")) {
            String friendly = buildAiFriendlyMessage(msg, name);
            return new ClassifiedError(ErrorType.AI_ERROR, friendly, e.getMessage());
        }

        // === 用户操作问题 ===
        if (msg.contains("empty") || msg.contains("blank") || msg.contains("null")
                || msg.contains("too large") || msg.contains("exceed")
                || msg.contains("invalid format") || msg.contains("unsupported")
                || msg.contains("not found") && msg.contains("technique")
                || name.contains("IllegalArgument")) {
            String friendly = buildUserFriendlyMessage(msg);
            return new ClassifiedError(ErrorType.USER_ERROR, friendly, e.getMessage());
        }

        // === 限流 ===
        if (msg.contains("rate") || name.contains("RateLimit")) {
            return new ClassifiedError(ErrorType.USER_ERROR,
                    "请求太频繁，请稍等片刻再试 ⏳", e.getMessage());
        }

        // === 默认：系统错误 ===
        log.error("系统内部错误", e);
        return new ClassifiedError(ErrorType.SYSTEM_ERROR,
                "系统内部错误，请稍后重试。如持续出现请联系管理员。", e.getMessage());
    }

    private String buildAiFriendlyMessage(String msg, String exceptionName) {
        if (msg.contains("429") || msg.contains("rate limit")) {
            return "AI服务繁忙，请稍等片刻再试 ⏳";
        }
        if (msg.contains("401") || msg.contains("403")) {
            return "AI服务认证失败，请联系管理员检查API密钥 🔑";
        }
        if (msg.contains("timeout") || msg.contains("timed out")) {
            return "AI响应超时，问题可能太复杂，试试简化问题或切换到快速模式 ⏱️";
        }
        if (msg.contains("connection")) {
            return "无法连接AI服务，请检查网络后重试 🌐";
        }
        if (msg.contains("model")) {
            return "AI模型暂时不可用，正在切换备用模型 🔄";
        }
        return "AI服务暂时异常，请稍后重试 🤖";
    }

    private String buildUserFriendlyMessage(String msg) {
        if (msg.contains("empty") || msg.contains("blank")) {
            return "请输入问题内容 ✍️";
        }
        if (msg.contains("too large") || msg.contains("exceed")) {
            return "文件太大了，单文件不超过5MB 📦";
        }
        if (msg.contains("technique") && msg.contains("not found")) {
            return "技法编号不存在，请从侧边栏选择可用技法 📋";
        }
        return "输入有误，请检查后重试 ⚠️";
    }
}
