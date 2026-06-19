package com.xuzhenwei.agent.session.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 会话 — 一个话题对应一个会话
 *
 * <p>每个会话是一段独立的"人机共想"对话线。用户可以创建多个会话，
 * 每个针对不同话题。会话可以随时打开继续对话或改进已有结果。</p>
 */
@Entity
@Table(name = "sessions")
public class Session {

    @Id
    @Column(length = 36)
    private String id;                    // UUID

    @Column(nullable = false, length = 200)
    private String name;                  // 首条消息前50字符自动生成

    @Column(length = 1000)
    private String topic;                 // LLM提取的话题摘要

    @Column(name = "project_id", length = 36)
    private String projectId;             // 关联项目（可空）

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private SessionStatus status = SessionStatus.ACTIVE;

    @Column(columnDefinition = "TEXT")
    private String summary;               // LLM生成的会话总结

    @Column(name = "technique_chain", columnDefinition = "TEXT")
    private String techniqueChain;        // JSON数组: ["001","025"]

    @Column(name = "message_count")
    private Integer messageCount = 0;

    @Column(name = "output_count")
    private Integer outputCount = 0;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();

    @Column(name = "last_message_at")
    private LocalDateTime lastMessageAt;

    @Column(length = 1000)
    private String tags;                  // 逗号分隔的标签

    public enum SessionStatus {
        ACTIVE, ARCHIVED, DELETED
    }

    public Session() {}

    public Session(String id, String name, String topic) {
        this.id = id;
        this.name = name;
        this.topic = topic;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    // ---- Getters / Setters ----

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public SessionStatus getStatus() { return status; }
    public void setStatus(SessionStatus status) { this.status = status; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public String getTechniqueChain() { return techniqueChain; }
    public void setTechniqueChain(String techniqueChain) { this.techniqueChain = techniqueChain; }

    public Integer getMessageCount() { return messageCount; }
    public void setMessageCount(Integer messageCount) { this.messageCount = messageCount; }

    public Integer getOutputCount() { return outputCount; }
    public void setOutputCount(Integer outputCount) { this.outputCount = outputCount; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public LocalDateTime getLastMessageAt() { return lastMessageAt; }
    public void setLastMessageAt(LocalDateTime lastMessageAt) { this.lastMessageAt = lastMessageAt; }

    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
}
