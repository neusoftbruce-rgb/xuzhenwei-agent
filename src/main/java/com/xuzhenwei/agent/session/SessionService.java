package com.xuzhenwei.agent.session;

import com.xuzhenwei.agent.project.MessageRecordRepository;
import com.xuzhenwei.agent.project.entity.MessageRecord;
import com.xuzhenwei.agent.session.entity.Session;
import com.xuzhenwei.agent.session.entity.SessionOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 会话服务 — 管理会话的创建、查询、更新、归档
 */
@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private final SessionRepository sessionRepository;
    private final SessionOutputRepository outputRepository;
    private final MessageRecordRepository messageRepository;

    public SessionService(SessionRepository sessionRepository,
                          SessionOutputRepository outputRepository,
                          MessageRecordRepository messageRepository) {
        this.sessionRepository = sessionRepository;
        this.outputRepository = outputRepository;
        this.messageRepository = messageRepository;
    }

    /**
     * 创建新会话（自动从首条消息提取名称）
     */
    @Transactional
    public Session createSession(String name, String topic, String projectId) {
        Session session = new Session();
        session.setId(UUID.randomUUID().toString());
        session.setName(name != null ? (name.length() > 50 ? name.substring(0, 50) : name) : "新会话");
        session.setTopic(topic);
        session.setProjectId(projectId);
        session.setStatus(Session.SessionStatus.ACTIVE);
        session.setMessageCount(0);
        session.setOutputCount(0);
        session.setCreatedAt(LocalDateTime.now());
        session.setUpdatedAt(LocalDateTime.now());
        session.setLastMessageAt(LocalDateTime.now());

        Session saved = sessionRepository.save(session);
        log.info("创建会话: {} ({})", saved.getId(), saved.getName());
        return saved;
    }

    /**
     * 获取或创建会话 — 如果 sessionId 为空则自动创建
     */
    @Transactional
    public Session getOrCreateSession(String sessionId, String firstMessage, String projectId) {
        if (sessionId != null && !sessionId.isBlank()) {
            Optional<Session> existing = sessionRepository.findById(sessionId);
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        // 自动创建
        String name = firstMessage != null ? firstMessage.substring(0, Math.min(firstMessage.length(), 50)) : "新会话";
        return createSession(name, firstMessage, projectId);
    }

    /**
     * 获取会话详情
     */
    public Optional<Session> getSession(String sessionId) {
        return sessionRepository.findById(sessionId);
    }

    /**
     * 列出活跃会话
     */
    public List<Session> listActiveSessions(int page, int size) {
        return sessionRepository.findByStatusOrderByLastMessageAtDesc(
                Session.SessionStatus.ACTIVE, PageRequest.of(page, size));
    }

    /**
     * 搜索会话
     */
    public List<Session> searchSessions(String query, int limit) {
        return sessionRepository.search(query, PageRequest.of(0, limit));
    }

    /**
     * 按项目过滤会话
     */
    public List<Session> listByProject(String projectId) {
        return sessionRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
    }

    /**
     * 更新会话
     */
    @Transactional
    public Optional<Session> updateSession(String sessionId, String name, String topic, String tags) {
        return sessionRepository.findById(sessionId).map(s -> {
            if (name != null) s.setName(name);
            if (topic != null) s.setTopic(topic);
            if (tags != null) s.setTags(tags);
            s.setUpdatedAt(LocalDateTime.now());
            return sessionRepository.save(s);
        });
    }

    /**
     * 归档会话
     */
    @Transactional
    public Optional<Session> archiveSession(String sessionId) {
        return sessionRepository.findById(sessionId).map(s -> {
            s.setStatus(Session.SessionStatus.ARCHIVED);
            s.setUpdatedAt(LocalDateTime.now());
            return sessionRepository.save(s);
        });
    }

    /**
     * 软删除会话
     */
    @Transactional
    public Optional<Session> deleteSession(String sessionId) {
        return sessionRepository.findById(sessionId).map(s -> {
            s.setStatus(Session.SessionStatus.DELETED);
            s.setUpdatedAt(LocalDateTime.now());
            return sessionRepository.save(s);
        });
    }

    /**
     * 更新会话的最后消息时间
     */
    @Transactional
    public void touchSession(String sessionId) {
        sessionRepository.findById(sessionId).ifPresent(s -> {
            s.setLastMessageAt(LocalDateTime.now());
            s.setMessageCount((int) messageRepository.countBySessionId(sessionId));
            s.setUpdatedAt(LocalDateTime.now());
            sessionRepository.save(s);
        });
    }

    /**
     * 追加技法到会话的技法链
     */
    @Transactional
    public void appendTechnique(String sessionId, String techniqueId) {
        sessionRepository.findById(sessionId).ifPresent(s -> {
            String chain = s.getTechniqueChain();
            if (chain == null || chain.isBlank()) {
                s.setTechniqueChain("[\"" + techniqueId + "\"]");
            } else {
                // 简单追加到 JSON 数组
                String updated = chain.substring(0, chain.length() - 1) + ",\"" + techniqueId + "\"]";
                s.setTechniqueChain(updated);
            }
            s.setUpdatedAt(LocalDateTime.now());
            sessionRepository.save(s);
        });
    }

    /**
     * 更新会话总结
     */
    @Transactional
    public void updateSummary(String sessionId, String summary) {
        sessionRepository.findById(sessionId).ifPresent(s -> {
            s.setSummary(summary);
            s.setUpdatedAt(LocalDateTime.now());
            sessionRepository.save(s);
        });
    }

    // ---- 会话产出管理 ----

    /**
     * 保存会话产出
     */
    @Transactional
    public SessionOutput saveOutput(String sessionId, SessionOutput.OutputFormat format,
                                     String title, String content, String techniqueLabel) {
        // 检查是否有同名产出，自动递增版本号
        List<SessionOutput> existing = outputRepository
                .findTop1BySessionIdAndTitleOrderByVersionNumberDesc(sessionId, title);
        int version = 1;
        Long parentId = null;
        if (!existing.isEmpty()) {
            SessionOutput prev = existing.get(0);
            version = prev.getVersionNumber() + 1;
            parentId = prev.getId();
        }

        SessionOutput output = new SessionOutput(sessionId, format, title, content, techniqueLabel);
        output.setVersionNumber(version);
        output.setParentOutputId(parentId);

        SessionOutput saved = outputRepository.save(output);

        // 更新会话产出计数（FIX-07: 仅统计当前会话产出，避免跨会话污染）
        sessionRepository.findById(sessionId).ifPresent(s -> {
            s.setOutputCount((int) outputRepository.countBySessionId(sessionId));
            sessionRepository.save(s);
        });

        log.info("保存会话产出: session={}, title={}, v{}", sessionId, title, version);
        return saved;
    }

    /**
     * 获取会话的所有产出
     */
    public List<SessionOutput> getOutputs(String sessionId) {
        return outputRepository.findBySessionIdOrderByCreatedAtDesc(sessionId);
    }

    /**
     * 获取会话的消息列表
     */
    public List<MessageRecord> getMessages(String sessionId, int page, int size) {
        return messageRepository.findBySessionIdOrderByCreatedAtDesc(
                sessionId, PageRequest.of(page, size));
    }

    /**
     * 获取会话的消息（按时间正序，用于加载上下文）
     */
    public List<MessageRecord> getMessagesAsc(String sessionId) {
        return messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
    }
}
