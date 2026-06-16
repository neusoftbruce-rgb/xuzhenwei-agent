package com.xuzhenwei.agent.project;

import com.xuzhenwei.agent.project.entity.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProjectRepository extends JpaRepository<Project, String> {

    List<Project> findByStatusOrderByUpdatedAtDesc(Project.ProjectStatus status);

    List<Project> findAllByOrderByUpdatedAtDesc();
}
