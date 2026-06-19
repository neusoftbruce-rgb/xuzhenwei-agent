package com.xuzhenwei.agent.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xuzhenwei.agent.project.entity.MessageRecord;
import com.xuzhenwei.agent.session.entity.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Memos 云端同步服务 — 将本地会话摘要同步到 Memos 云端
 *
 * <p>Memos 作为辅助存储，提供跨设备访问和长期知识积累。
 * 同步策略：本地 DB 为主，Memos 异步写入（非阻塞），失败不影响本地功能。</p>
 *
 * <p>使用 Memos REST API: POST /api/v1/memos</p>
 *
 * <p>v2.3: 使用 Jackson 构建 JSON（FIX-04），消除手动字符串拼接的注入风险</p>
 */
@Service
public class MemosSyncService {

    private static final Logger log = LoggerFactory.getLogger(MemosSyncService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final SessionService sessionService;
    private final HttpClient httpClient;

    @Value("${agent.memos.enabled:false}")
    private boolean enabled;

    @Value("${agent.memos.base-url:https://neusoftbruce-xuzhenwei.hf.space}")
    private String baseUrl;

    @Value("${agent.memos.api-key:}")
    private String apiKey;

    public MemosSyncService(SessionService sessionService) {
        this.sessionService = sessionService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * 同步会话摘要到 Memos（异步，非阻塞）
     */
    @Async
    public void syncSessionAsync(String sessionId) {
        if (!enabled) {
            log.debug("Memos 同步未启用，跳过");
            return;
        }
        try {
            syncSession(sessionId);
        } catch (Exception e) {
            log.warn("Memos 同步失败（不影响本地功能）: {}", e.getMessage());
        }
    }

    /**
     * 同步会话到 Memos
     */
    public String syncSession(String sessionId) throws Exception {
        if (!enabled) {
            return null;
        }

        var session = sessionService.getSession(sessionId).orElse(null);
        if (session == null) {
            log.warn("会话不存在: {}", sessionId);
            return null;
        }

        var messages = sessionService.getMessagesAsc(sessionId);

        // 构建 Memo 内容（Markdown）
        String content = buildMemoContent(session, messages);

        // 构建请求体（v2.3: Jackson安全序列化）
        String requestBody = buildMemoRequestBodyJackson(session, content);

        // 发送请求
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/memos"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofSeconds(15))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200 || response.statusCode() == 201) {
            log.info("会话 {} 已同步到 Memos", sessionId);
            return response.body();
        } else {
            log.warn("Memos 同步返回非预期状态码: {} - {}", response.statusCode(), response.body());
            throw new RuntimeException("Memos sync failed: " + response.statusCode());
        }
    }

    /**
     * 构建 Markdown 格式的 Memo 内容
     */
    private String buildMemoContent(Session session, List<MessageRecord> messages) {
        var sb = new StringBuilder();

        sb.append("# 会话: ").append(session.getName()).append("\n\n");

        if (session.getTopic() != null) {
            sb.append("**话题**: ").append(session.getTopic()).append("\n\n");
        }

        sb.append("**日期**: ").append(formatDateTime(session.getCreatedAt())).append("\n");
        sb.append("**消息数**: ").append(session.getMessageCount()).append("\n");

        if (session.getTechniqueChain() != null) {
            sb.append("**使用技法**: ").append(session.getTechniqueChain()).append("\n");
        }
        sb.append("\n");

        // 摘要
        if (session.getSummary() != null) {
            sb.append("## 摘要\n\n").append(session.getSummary()).append("\n\n");
        } else {
            sb.append("## 摘要\n\n");
            int shown = 0;
            for (int i = Math.max(0, messages.size() - 6); i < messages.size() && shown < 6; i++) {
                var msg = messages.get(i);
                if (msg.getRole() == MessageRecord.MessageRole.SYSTEM) continue;
                String roleLabel = msg.getRole() == MessageRecord.MessageRole.USER ? "用户" : "徐振伟";
                String content = msg.getContent();
                if (content != null && content.length() > 200) {
                    content = content.substring(0, 200) + "...";
                }
                sb.append("- **").append(roleLabel).append("**: ").append(content).append("\n");
                shown++;
            }
            sb.append("\n");
        }

        sb.append("---\n");
        sb.append("*由 徐振伟智能体 v2.3 自动同步 · ")
          .append(formatDateTime(LocalDateTime.now())).append("*\n");

        return sb.toString();
    }

    /**
     * v2.3: 使用 Jackson 安全构建 JSON 请求体（替代手动字符串拼接）
     */
    private String buildMemoRequestBodyJackson(Session session, String content) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("content", content);
        root.put("visibility", "PRIVATE");

        // payload
        ObjectNode payload = objectMapper.createObjectNode();
        ObjectNode properties = objectMapper.createObjectNode();
        properties.put("sessionId", session.getId());
        properties.put("projectId", session.getProjectId() != null ? session.getProjectId() : "");
        properties.put("messageCount", session.getMessageCount() != null ? session.getMessageCount() : 0);
        properties.put("techniqueChain", session.getTechniqueChain() != null ? session.getTechniqueChain() : "");
        properties.put("source", "xuzhenwei-agent");
        payload.set("properties", properties);

        ArrayNode tags = objectMapper.createArrayNode();
        tags.add("session");
        tags.add("xuzhenwei-agent");
        payload.set("tags", tags);

        root.set("payload", payload);

        return objectMapper.writeValueAsString(root);
    }

    // ---- 向后兼容（保留旧方法，标记deprecated） ----

    /** @deprecated v2.3起使用 buildMemoRequestBodyJackson 替代 */
    @Deprecated
    private String buildMemoRequestBody(Session session, String content) {
        try {
            return buildMemoRequestBodyJackson(session, content);
        } catch (Exception e) {
            log.error("Jackson序列化失败，回退手动拼接", e);
            return fallbackBuild(session, content);
        }
    }

    private String fallbackBuild(Session session, String content) {
        String escapedContent = content.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
        return "{" +
                "\"content\":\"" + escapedContent + "\"," +
                "\"visibility\":\"PRIVATE\"," +
                "\"payload\":{" +
                "\"properties\":{" +
                "\"sessionId\":\"" + escapeJson(session.getId()) + "\"," +
                "\"source\":\"xuzhenwei-agent\"" +
                "}," +
                "\"tags\":[\"session\",\"xuzhenwei-agent\"]" +
                "}}";
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String formatDateTime(LocalDateTime dt) {
        if (dt == null) return "";
        return dt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }
}
