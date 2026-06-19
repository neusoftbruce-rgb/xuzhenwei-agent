package com.xuzhenwei.agent.project.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 消息记录 — 持久化存储每一轮对话
 *
 * <p>记录了完整的"人机共想"过程：哪条技法、哪个步骤、说了什么。
 * 可用于后续分析哪些技法对哪些课题最有效。</p>
 *
 * <p>v2.2: 新增 sessionId（会话关联）、eventType（消息类型）、metadata（JSON元数据）</p>
 */
@Entity
@Table(name = "message_records", indexes = {
    @Index(name = "idx_msg_session", columnList = "session_id, created_at"),
    @Index(name = "idx_msg_project", columnList = "project_id")
})
public class MessageRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", length = 36)
    private String projectId;

    @Column(name = "session_id", length = 36)
    private String sessionId;             // v2.2: 关联会话

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private MessageRole role;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(name = "technique_id", length = 10)
    private String techniqueId;    // 使用的技法编号

    @Column(name = "step_number")
    private Integer stepNumber;    // 技法步骤号

    @Column(name = "event_type", length = 30)
    private String eventType;            // v2.2: USER_MSG / AI_RESPONSE / IMPROVEMENT_INSTRUCTION / IMPROVED_OUTPUT

    @Column(name = "metadata", columnDefinition = "TEXT")
    private String metadata;             // v2.2: JSON元数据 {techniqueLabel, stepName, ...}

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    public enum MessageRole {
        USER, ASSISTANT, SYSTEM
    }

    public MessageRecord() {}

    public MessageRecord(String projectId, MessageRole role, String content,
                         String techniqueId, Integer stepNumber) {
        this.projectId = projectId;
        this.role = role;
        this.content = content;
        this.techniqueId = techniqueId;
        this.stepNumber = stepNumber;
    }

    // ---- Getters / Setters ----

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public MessageRole getRole() { return role; }
    public void setRole(MessageRole role) { this.role = role; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getTechniqueId() { return techniqueId; }
    public void setTechniqueId(String techniqueId) { this.techniqueId = techniqueId; }

    public Integer getStepNumber() { return stepNumber; }
    public void setStepNumber(Integer stepNumber) { this.stepNumber = stepNumber; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public String getMetadata() { return metadata; }
    public void setMetadata(String metadata) { this.metadata = metadata; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
