package com.xuzhenwei.agent.agent;

import com.xuzhenwei.agent.project.MessageRecordRepository;
import com.xuzhenwei.agent.project.entity.MessageRecord;
import com.xuzhenwei.agent.session.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 对话管理器 — 管理多轮"人机共想"对话的上下文
 *
 * <p>v2.2: 从纯内存升级为 内存缓存 + DB 持久化双写。
 * append() 同时写内存和数据库，getContext() 优先读内存、回退读数据库。</p>
 *
 * <p>每种技法有自己的对话线（conversationId + techniqueId 隔离），
 * 防止技法间的上下文互相污染。</p>
 */
@Component
public class ConversationManager {

    private static final Logger log = LoggerFactory.getLogger(ConversationManager.class);

    /** 会话上下文存储：key = conversationId, value = 历史消息列表（内存缓存） */
    private final Map<String, List<ContextMessage>> conversations = new ConcurrentHashMap<>();

    /** 会话最后访问时间（用于LRU淘汰） */
    private final Map<String, Long> lastAccessTime = new ConcurrentHashMap<>();

    /** v3.4: 会话元数据存储 (key = convId, value = metadata map) */
    private final Map<String, Map<String, String>> metadata = new ConcurrentHashMap<>();

    /** 每个会话最多保留多少条历史消息 (v2.5: 40→80) */
    private static final int MAX_HISTORY = 80;

    /** 超过此阈值后，旧消息自动压缩为摘要 (v2.5新增) */
    private static final int SUMMARY_THRESHOLD = 20;

    /** 内存缓存最多保留会话数 */
    private static final int MAX_CACHED_SESSIONS = 100;

    private final MessageRecordRepository messageRepository;
    private final SessionService sessionService;

    public ConversationManager(MessageRecordRepository messageRepository,
                               SessionService sessionService) {
        this.messageRepository = messageRepository;
        this.sessionService = sessionService;
    }

    /**
     * 获取会话的完整上下文（用于构造 LLM 请求）
     * 优先读内存缓存，未命中则从数据库加载
     */
    public List<ContextMessage> getContext(String conversationId) {
        // 1. 先查内存缓存
        var cached = conversations.get(conversationId);
        if (cached != null && !cached.isEmpty()) {
            lastAccessTime.put(conversationId, System.currentTimeMillis()); // LRU tracking
            return cached;
        }

        // 2. 内存未命中，尝试从数据库加载
        // conversationId 可能是 "sessionId" 或 "sessionId_techniqueId" 的复合键
        String sessionId = extractSessionId(conversationId);
        List<MessageRecord> dbMessages = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);

        if (dbMessages.isEmpty()) {
            return List.of();
        }

        // 限制条数
        if (dbMessages.size() > MAX_HISTORY) {
            dbMessages = dbMessages.subList(dbMessages.size() - MAX_HISTORY, dbMessages.size());
        }

        // 转换为 ContextMessage 并缓存
        List<ContextMessage> fromDb = dbMessages.stream()
                .map(m -> new ContextMessage(m.getRole().name(), m.getContent()))
                .collect(Collectors.toList());

        conversations.put(conversationId, fromDb);
        log.debug("从 DB 加载会话[{}] 上下文，{} 条消息", conversationId, fromDb.size());
        return fromDb;
    }

    /**
     * 添加一条消息到会话历史（双写：内存 + 数据库）
     */
    public void append(String conversationId, String role, String content) {
        // 1. 内存缓存
        conversations.computeIfAbsent(conversationId, k -> new ArrayList<>())
                .add(new ContextMessage(role, content));
        lastAccessTime.put(conversationId, System.currentTimeMillis());

        // 超过上限时裁剪旧消息
        var history = conversations.get(conversationId);
        while (history.size() > MAX_HISTORY) {
            history.remove(0);
        }

        // 2. 持久化到数据库
        try {
            String sessionId = extractSessionId(conversationId);
            String techniqueId = extractTechniqueId(conversationId);

            MessageRecord.MessageRole msgRole = switch (role) {
                case "用户", "USER" -> MessageRecord.MessageRole.USER;
                case "徐振伟", "ASSISTANT" -> MessageRecord.MessageRole.ASSISTANT;
                default -> MessageRecord.MessageRole.SYSTEM;
            };

            MessageRecord record = new MessageRecord();
            record.setSessionId(sessionId);
            record.setRole(msgRole);
            record.setContent(content);
            record.setTechniqueId(techniqueId);
            record.setEventType(msgRole == MessageRecord.MessageRole.USER ? "USER_MSG" : "AI_RESPONSE");
            record.setCreatedAt(LocalDateTime.now());

            messageRepository.save(record);

            // 3. 更新会话元数据
            sessionService.touchSession(sessionId);

        } catch (Exception e) {
            log.warn("消息持久化失败（不影响内存操作）: {}", e.getMessage());
        }

        log.debug("会话[{}] 新增 {} 消息，当前内存 {} 条", conversationId, role, history.size());
    }

    /**
     * 添加消息并指定事件类型和元数据
     */
    public void append(String conversationId, String role, String content,
                       String eventType, String metadata) {
        // 内存缓存
        conversations.computeIfAbsent(conversationId, k -> new ArrayList<>())
                .add(new ContextMessage(role, content));

        var history = conversations.get(conversationId);
        while (history.size() > MAX_HISTORY) {
            history.remove(0);
        }

        // DB 持久化
        try {
            String sessionId = extractSessionId(conversationId);
            String techniqueId = extractTechniqueId(conversationId);

            MessageRecord.MessageRole msgRole = switch (role) {
                case "用户", "USER" -> MessageRecord.MessageRole.USER;
                case "徐振伟", "ASSISTANT" -> MessageRecord.MessageRole.ASSISTANT;
                default -> MessageRecord.MessageRole.SYSTEM;
            };

            MessageRecord record = new MessageRecord();
            record.setSessionId(sessionId);
            record.setRole(msgRole);
            record.setContent(content);
            record.setTechniqueId(techniqueId);
            record.setEventType(eventType);
            record.setMetadata(metadata);
            record.setCreatedAt(LocalDateTime.now());

            messageRepository.save(record);
            sessionService.touchSession(sessionId);

        } catch (Exception e) {
            log.warn("消息持久化失败: {}", e.getMessage());
        }
    }

    /** v3.4: 设置会话元数据 */
    public void setMetadata(String conversationId, String key, String value) {
        metadata.computeIfAbsent(conversationId, k -> new ConcurrentHashMap<>()).put(key, value);
    }

    /** v3.4: 获取会话元数据 */
    public String getMetadata(String conversationId, String key) {
        var m = metadata.get(conversationId);
        return m != null ? m.get(key) : null;
    }

    /**
     * 清除某个会话（内存 + DB 标记删除）
     */
    public void clear(String conversationId) {
        conversations.remove(conversationId);
    }

    /**
     * 将会话历史转为 Prompt 可用的文本格式
     * v2.5: 只取最近N条, 旧消息标注省略, 避免上下文溢出
     */
    public String formatHistory(String conversationId) {
        return formatHistory(conversationId, 10);
    }

    /**
     * @param maxMessages 最多包含的消息条数
     */
    public String formatHistory(String conversationId, int maxMessages) {
        var history = getContext(conversationId);
        if (history.isEmpty()) return "";

        var sb = new StringBuilder();
        int total = history.size();
        int start = Math.max(0, total - maxMessages);

        if (start > 0) {
            sb.append("## 历史对话 (省略了前").append(start).append("条早期消息)\n");
        } else {
            sb.append("## 历史对话\n");
        }

        for (int i = start; i < total; i++) {
            var msg = history.get(i);
            String content = msg.content;
            // 单条消息超过500字截断
            if (content != null && content.length() > 500) {
                content = content.substring(0, 500) + "...";
            }
            sb.append("**").append(msg.role).append("**: ").append(content).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 从复合键中提取 sessionId
     * conversationId 格式: "sessionId" 或 "sessionId_techniqueId"
     */
    private String extractSessionId(String conversationId) {
        if (conversationId == null) return "unknown";
        int idx = conversationId.lastIndexOf('_');
        // 如果下划线后面看起来像技法ID（短且无连字符），则截取前面部分
        if (idx > 0 && idx < conversationId.length() - 1) {
            String after = conversationId.substring(idx + 1);
            // 技法ID通常是纯数字或TIPS-xx格式
            if (after.matches("\\d{3}") || after.startsWith("TIPS-") || after.length() <= 6) {
                return conversationId.substring(0, idx);
            }
        }
        return conversationId;
    }

    /**
     * 从复合键中提取 techniqueId
     */
    private String extractTechniqueId(String conversationId) {
        if (conversationId == null) return null;
        int idx = conversationId.lastIndexOf('_');
        if (idx > 0 && idx < conversationId.length() - 1) {
            String after = conversationId.substring(idx + 1);
            if (after.matches("\\d{3}") || after.startsWith("TIPS-") || after.length() <= 6) {
                return after;
            }
        }
        return null;
    }

    /**
     * 定期清理长时间未访问的内存缓存（由调度任务调用）
     * v2.3: 基于最后访问时间的LRU淘汰，不再随机删除
     */
    public void evictStaleEntries() {
        if (conversations.size() > MAX_CACHED_SESSIONS) {
            // 按最后访问时间排序，淘汰最旧的条目
            int toRemove = conversations.size() - MAX_CACHED_SESSIONS;
            lastAccessTime.entrySet().stream()
                    .sorted(Map.Entry.comparingByValue())
                    .limit(toRemove)
                    .forEach(e -> {
                        conversations.remove(e.getKey());
                        lastAccessTime.remove(e.getKey());
                    });
            log.info("LRU淘汰了 {} 个过期会话缓存", toRemove);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 方法9: 结果缓存与复用
    // ═══════════════════════════════════════════════════════════

    /** 近期分析结果缓存 */
    private final Map<String, CachedResponse> responseCache = new ConcurrentHashMap<>();
    private static final int MAX_CACHED_RESPONSES = 50;
    private static final double SIMILARITY_THRESHOLD = 0.85;

    public record CachedResponse(
            String userQuestion, String techniqueId, String techniqueName,
            String responseSummary, long timestamp
    ) {}

    /** 查找相似问题的缓存结果 */
    public CachedResponse findSimilar(String userQuestion) {
        if (userQuestion == null || userQuestion.isBlank()) return null;
        String fp = fingerprint(userQuestion);
        var exact = responseCache.get(fp);
        if (exact != null) return exact;
        CachedResponse best = null;
        double bestSim = 0;
        for (var entry : responseCache.entrySet()) {
            double sim = similarity(fp, entry.getKey());
            if (sim > bestSim) { bestSim = sim; best = entry.getValue(); }
        }
        return (best != null && bestSim >= SIMILARITY_THRESHOLD) ? best : null;
    }

    /** 存入缓存 */
    public void cacheResponse(String userQuestion, String techniqueId,
                               String techniqueName, String responseContent) {
        if (userQuestion == null || responseContent == null) return;
        String fp = fingerprint(userQuestion);
        String summary = responseContent.length() > 200 ? responseContent.substring(0, 200) : responseContent;
        responseCache.put(fp, new CachedResponse(userQuestion, techniqueId, techniqueName, summary, System.currentTimeMillis()));
        if (responseCache.size() > MAX_CACHED_RESPONSES) {
            responseCache.entrySet().stream()
                    .sorted(Map.Entry.comparingByValue((a, b) -> Long.compare(a.timestamp, b.timestamp)))
                    .limit(responseCache.size() - MAX_CACHED_RESPONSES)
                    .forEach(e -> responseCache.remove(e.getKey()));
        }
    }

    private String fingerprint(String text) {
        return text.toLowerCase().replaceAll("[\\s\\p{Punct}，。！？、；：\"\"''【】《》（）…—·]", "");
    }

    private double similarity(String a, String b) {
        if (a.equals(b)) return 1.0;
        int maxLen = Math.max(a.length(), b.length());
        if (maxLen == 0) return 1.0;
        int lcs = lcs(a, b);
        return (double) lcs / Math.min(a.length(), b.length());
    }

    private int lcs(String a, String b) {
        int m = a.length(), n = b.length();
        int[] prev = new int[n + 1], curr = new int[n + 1];
        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                curr[j] = (a.charAt(i - 1) == b.charAt(j - 1)) ? prev[j - 1] + 1 : Math.max(prev[j], curr[j - 1]);
            }
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return prev[n];
    }

    /**
     * 上下文消息
     */
    public record ContextMessage(String role, String content) {}
}
