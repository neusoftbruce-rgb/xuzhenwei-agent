package com.xuzhenwei.agent.project.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 消息记录 — 持久化存储每一轮对话
 *
 * <p>记录了完整的"人机共想"过程：哪条技法、哪个步骤、说了什么。
 * 可用于后续分析哪些技法对哪些课题最有效。</p>
 */
@Entity
@Table(name = "message_records")
public class MessageRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false, length = 36)
    private String projectId;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private MessageRole role;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(name = "technique_id", length = 10)
    private String techniqueId;    // 使用的技法编号

    @Column(name = "step_number")
    private Integer stepNumber;    // 技法步骤号

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

    public MessageRole getRole() { return role; }
    public void setRole(MessageRole role) { this.role = role; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getTechniqueId() { return techniqueId; }
    public void setTechniqueId(String techniqueId) { this.techniqueId = techniqueId; }

    public Integer getStepNumber() { return stepNumber; }
    public void setStepNumber(Integer stepNumber) { this.stepNumber = stepNumber; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
