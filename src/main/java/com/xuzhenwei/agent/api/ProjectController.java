package com.xuzhenwei.agent.api;

import com.xuzhenwei.agent.project.ProjectService;
import com.xuzhenwei.agent.project.entity.Project;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 项目管理 API
 */
@RestController
@RequestMapping("/api/projects")
@CrossOrigin
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    /** 创建新项目 */
    @PostMapping
    public Project create(@RequestBody CreateProjectRequest request) {
        return projectService.create(request.name(), request.topic());
    }

    /** 项目列表 */
    @GetMapping
    public List<Project> listAll() {
        return projectService.listAll();
    }

    /** 项目详情 */
    @GetMapping("/{id}")
    public ResponseEntity<Project> getById(@PathVariable String id) {
        return projectService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** 归档项目 */
    @PutMapping("/{id}/archive")
    public ResponseEntity<Map<String, String>> archive(@PathVariable String id) {
        projectService.archive(id);
        return ResponseEntity.ok(Map.of("status", "archived"));
    }

    public record CreateProjectRequest(String name, String topic) {}
}
