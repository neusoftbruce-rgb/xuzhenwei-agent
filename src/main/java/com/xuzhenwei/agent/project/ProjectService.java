package com.xuzhenwei.agent.project;

import com.xuzhenwei.agent.project.entity.Project;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 项目管理服务
 */
@Service
public class ProjectService {

    private final ProjectRepository repository;

    public ProjectService(ProjectRepository repository) {
        this.repository = repository;
    }

    /** 创建新项目 */
    public Project create(String name, String topic) {
        var project = new Project(UUID.randomUUID().toString(), name, topic);
        return repository.save(project);
    }

    /** 获取所有活跃项目 */
    public List<Project> listActive() {
        return repository.findByStatusOrderByUpdatedAtDesc(Project.ProjectStatus.ACTIVE);
    }

    /** 获取所有项目 */
    public List<Project> listAll() {
        return repository.findAllByOrderByUpdatedAtDesc();
    }

    /** 按 ID 查找 */
    public Optional<Project> findById(String id) {
        return repository.findById(id);
    }

    /** 归档项目 */
    public void archive(String id) {
        repository.findById(id).ifPresent(p -> {
            p.setStatus(Project.ProjectStatus.ARCHIVED);
            repository.save(p);
        });
    }
}
