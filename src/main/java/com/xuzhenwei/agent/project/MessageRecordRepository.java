package com.xuzhenwei.agent.project;

import com.xuzhenwei.agent.project.entity.MessageRecord;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 消息记录 Repository
 */
@Repository
public interface MessageRecordRepository extends JpaRepository<MessageRecord, Long> {

    /** 按会话ID加载消息（最旧→最新） */
    List<MessageRecord> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    /** 按会话ID分页加载（最新→最旧） */
    List<MessageRecord> findBySessionIdOrderByCreatedAtDesc(String sessionId, Pageable pageable);

    /** 加载指定时间之前的消息（用于继续对话时加载历史） */
    List<MessageRecord> findBySessionIdAndCreatedAtBeforeOrderByCreatedAtDesc(
            String sessionId, java.time.LocalDateTime before, Pageable pageable);

    /** 统计会话消息数 */
    long countBySessionId(String sessionId);

    /** 删除会话下所有消息 */
    void deleteBySessionId(String sessionId);

    /** 获取会话最后一条消息 */
    List<MessageRecord> findTop1BySessionIdOrderByCreatedAtDesc(String sessionId);
}
