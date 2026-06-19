package com.xuzhenwei.agent.session;

import com.xuzhenwei.agent.session.entity.Session;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 会话 Repository
 */
@Repository
public interface SessionRepository extends JpaRepository<Session, String> {

    /** 按状态查询，按最后消息时间倒序 */
    List<Session> findByStatusOrderByLastMessageAtDesc(Session.SessionStatus status, Pageable pageable);

    /** 按项目ID查询 */
    List<Session> findByProjectIdOrderByCreatedAtDesc(String projectId);

    /** 按技法链包含指定技法ID查询 */
    @Query("SELECT s FROM Session s WHERE s.techniqueChain LIKE %:techniqueId%")
    List<Session> findByTechniqueChainContaining(@Param("techniqueId") String techniqueId);

    /** 搜索会话（名称、话题、标签） */
    @Query("SELECT s FROM Session s WHERE " +
           "LOWER(s.name) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
           "LOWER(COALESCE(s.topic, '')) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
           "LOWER(COALESCE(s.tags, '')) LIKE LOWER(CONCAT('%', :q, '%')) " +
           "ORDER BY s.lastMessageAt DESC")
    List<Session> search(@Param("q") String q, Pageable pageable);

    /** 按状态计数 */
    long countByStatus(Session.SessionStatus status);
}
