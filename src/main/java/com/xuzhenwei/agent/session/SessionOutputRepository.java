package com.xuzhenwei.agent.session;

import com.xuzhenwei.agent.session.entity.SessionOutput;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 会话产出 Repository
 */
@Repository
public interface SessionOutputRepository extends JpaRepository<SessionOutput, Long> {

    /** 按会话ID查询所有产出 */
    List<SessionOutput> findBySessionIdOrderByCreatedAtDesc(String sessionId);

    /** 按会话ID和格式查询 */
    List<SessionOutput> findBySessionIdAndFormatOrderByCreatedAtDesc(
            String sessionId, SessionOutput.OutputFormat format);

    /** 获取某产出的最新版本号 */
    List<SessionOutput> findTop1BySessionIdAndTitleOrderByVersionNumberDesc(
            String sessionId, String title);

    /** 删除会话下所有产出 */
    void deleteBySessionId(String sessionId);

    /** 统计会话产出数量 */
    long countBySessionId(String sessionId);
}
