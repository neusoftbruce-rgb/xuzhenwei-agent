package com.xuzhenwei.agent.project.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 项目实体 — 一个项目对应一个课题
 *
 * <p>客户每次发起一个创意课题（如"如何提升农产品复购率"），
 * 就创建一个 Project。一个 Project 下可有多轮对话。</p>
 */
@Entity
@Table(name = "projects")
public class Project {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 500)
    private String topic;          // 课题描述

    @Column(length = 20)
    @Enumerated(EnumType.STRING)
    private ProjectStatus status = ProjectStatus.ACTIVE;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();

    public enum ProjectStatus {
        ACTIVE, ARCHIVED, DELETED
    }

    public Project() {}

    public Project(String id, String name, String topic) {
        this.id = id;
        this.name = name;
        this.topic = topic;
    }

    // ---- Getters / Setters ----

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }

    public ProjectStatus getStatus() { return status; }
    public void setStatus(ProjectStatus status) { this.status = status; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
