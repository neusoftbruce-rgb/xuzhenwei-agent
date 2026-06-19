package com.xuzhenwei.agent.session.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 会话产出 — 会话中生成的报告/文档
 *
 * <p>支持版本链：改进后的产出通过 parentOutputId 链接到上一版本，
 * versionNumber 自增，形成 v1 → v2 → v3 的改进追溯链。</p>
 */
@Entity
@Table(name = "session_outputs")
public class SessionOutput {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, length = 36)
    private String sessionId;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private OutputFormat format;

    @Column(nullable = false, length = 300)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(name = "technique_label", columnDefinition = "TEXT")
    private String techniqueLabel;

    @Column(name = "version_number")
    private Integer versionNumber = 1;

    @Column(name = "parent_output_id")
    private Long parentOutputId;          // 上一版本ID

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    public enum OutputFormat {
        WORD, EXCEL, PPT, MARKDOWN
    }

    public SessionOutput() {}

    public SessionOutput(String sessionId, OutputFormat format, String title,
                         String content, String techniqueLabel) {
        this.sessionId = sessionId;
        this.format = format;
        this.title = title;
        this.content = content;
        this.techniqueLabel = techniqueLabel;
    }

    // ---- Getters / Setters ----

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public OutputFormat getFormat() { return format; }
    public void setFormat(OutputFormat format) { this.format = format; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getTechniqueLabel() { return techniqueLabel; }
    public void setTechniqueLabel(String techniqueLabel) { this.techniqueLabel = techniqueLabel; }

    public Integer getVersionNumber() { return versionNumber; }
    public void setVersionNumber(Integer versionNumber) { this.versionNumber = versionNumber; }

    public Long getParentOutputId() { return parentOutputId; }
    public void setParentOutputId(Long parentOutputId) { this.parentOutputId = parentOutputId; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
