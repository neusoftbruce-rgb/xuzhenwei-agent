package com.xuzhenwei.agent.agent;

import com.xuzhenwei.agent.project.entity.MessageRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对话管理器 — 管理多轮"人机共想"对话的上下文
 *
 * <p>设计思路：
 * 不用 Spring AI 的 ChatMemory（API 在不同版本间变化大），
 * 而是自己维护一个轻量的会话上下文 Map。</p>
 *
 * <p>每种技法有自己的对话线（conversationId + techniqueId 隔离），
 * 防止技法间的上下文互相污染。</p>
 *
 * <p>Phase 2 将切到数据库持久化（MessageRecord 表）。</p>
 */
@Component
public class ConversationManager {

    private static final Logger log = LoggerFactory.getLogger(ConversationManager.class);

    /** 会话上下文存储：key = conversationId, value = 历史消息列表 */
    private final Map<String, List<ContextMessage>> conversations = new ConcurrentHashMap<>();

    /** 每个会话最多保留多少条历史消息 */
    private static final int MAX_HISTORY = 20;

    /**
     * 获取会话的完整上下文（用于构造 LLM 请求）
     */
    public List<ContextMessage> getContext(String conversationId) {
        return conversations.getOrDefault(conversationId, List.of());
    }

    /**
     * 添加一条消息到会话历史
     */
    public void append(String conversationId, String role, String content) {
        conversations.computeIfAbsent(conversationId, k -> new ArrayList<>())
                .add(new ContextMessage(role, content));

        // 超过上限时裁剪旧消息
        var history = conversations.get(conversationId);
        while (history.size() > MAX_HISTORY) {
            history.remove(0);
        }

        log.debug("会话[{}] 新增 {} 消息，当前 {} 条", conversationId, role, history.size());
    }

    /**
     * 清除某个会话
     */
    public void clear(String conversationId) {
        conversations.remove(conversationId);
    }

    /**
     * 将会话历史转为 Prompt 可用的文本格式
     */
    public String formatHistory(String conversationId) {
        var history = getContext(conversationId);
        if (history.isEmpty()) return "";

        var sb = new StringBuilder("## 历史对话\n");
        for (var msg : history) {
            sb.append("**").append(msg.role).append("**: ").append(msg.content).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 上下文消息
     */
    public record ContextMessage(String role, String content) {}
}
