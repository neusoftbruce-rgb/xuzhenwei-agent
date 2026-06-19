package com.xuzhenwei.agent.api;

import com.xuzhenwei.agent.project.entity.MessageRecord;
import com.xuzhenwei.agent.session.SessionService;
import com.xuzhenwei.agent.session.entity.Session;
import com.xuzhenwei.agent.session.entity.SessionOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 会话管理 API — v2.2 新增
 *
 * <p>提供会话的 CRUD、消息检索、产出管理、Memos 同步等功能。</p>
 */
@RestController
@RequestMapping("/api/sessions")
@CrossOrigin
public class SessionController {

    private static final Logger log = LoggerFactory.getLogger(SessionController.class);

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    // ========== 会话 CRUD ==========

    /** 创建新会话 */
    @PostMapping
    public ResponseEntity<Session> createSession(@RequestBody Map<String, String> body) {
        String name = body.getOrDefault("name", "新会话");
        String topic = body.getOrDefault("topic", null);
        String projectId = body.getOrDefault("projectId", null);
        Session session = sessionService.createSession(name, topic, projectId);
        return ResponseEntity.ok(session);
    }

    /** 列出活跃会话 */
    @GetMapping
    public ResponseEntity<List<Session>> listSessions(
            @RequestParam(defaultValue = "ACTIVE") String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Session.SessionStatus st;
        try {
            st = Session.SessionStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            st = Session.SessionStatus.ACTIVE;
        }
        // 使用 SessionRepository 的自定义查询
        var sessions = sessionService.listActiveSessions(page, size);
        return ResponseEntity.ok(sessions);
    }

    /** 搜索会话 */
    @GetMapping("/search")
    public ResponseEntity<List<Session>> searchSessions(
            @RequestParam String q,
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(sessionService.searchSessions(q, limit));
    }

    /** 获取会话详情 */
    @GetMapping("/{id}")
    public ResponseEntity<Session> getSession(@PathVariable String id) {
        return sessionService.getSession(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** 更新会话 */
    @PutMapping("/{id}")
    public ResponseEntity<Session> updateSession(@PathVariable String id,
                                                  @RequestBody Map<String, String> body) {
        return sessionService.updateSession(id,
                body.get("name"),
                body.get("topic"),
                body.get("tags"))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** 归档会话 */
    @PutMapping("/{id}/archive")
    public ResponseEntity<Session> archiveSession(@PathVariable String id) {
        return sessionService.archiveSession(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** 软删除会话 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> deleteSession(@PathVariable String id) {
        return sessionService.deleteSession(id)
                .map(s -> ResponseEntity.ok(Map.of("status", "deleted", "id", id)))
                .orElse(ResponseEntity.notFound().build());
    }

    // ========== 消息检索 ==========

    /** 获取会话消息列表（分页，最新在前） */
    @GetMapping("/{id}/messages")
    public ResponseEntity<List<MessageRecord>> getMessages(
            @PathVariable String id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        List<MessageRecord> messages = sessionService.getMessages(id, page, size);
        return ResponseEntity.ok(messages);
    }

    /** 获取会话消息（时间正序，用于加载上下文） */
    @GetMapping("/{id}/messages/asc")
    public ResponseEntity<List<MessageRecord>> getMessagesAsc(@PathVariable String id) {
        return ResponseEntity.ok(sessionService.getMessagesAsc(id));
    }

    // ========== 产出管理 ==========

    /** 获取会话产出列表 */
    @GetMapping("/{id}/outputs")
    public ResponseEntity<List<SessionOutput>> getOutputs(@PathVariable String id) {
        return ResponseEntity.ok(sessionService.getOutputs(id));
    }

    /** 保存会话产出 */
    @PostMapping("/{id}/outputs")
    public ResponseEntity<SessionOutput> saveOutput(@PathVariable String id,
                                                     @RequestBody Map<String, String> body) {
        String formatStr = body.getOrDefault("format", "MARKDOWN").toUpperCase();
        SessionOutput.OutputFormat format;
        try {
            format = SessionOutput.OutputFormat.valueOf(formatStr);
        } catch (IllegalArgumentException e) {
            format = SessionOutput.OutputFormat.MARKDOWN;
        }
        String title = body.getOrDefault("title", "未命名产出");
        String content = body.getOrDefault("content", "");
        String techniqueLabel = body.getOrDefault("techniqueLabel", "");

        SessionOutput output = sessionService.saveOutput(id, format, title, content, techniqueLabel);
        return ResponseEntity.ok(output);
    }

    // ========== 摘要与同步 ==========

    /** 更新会话总结 */
    @PutMapping("/{id}/summary")
    public ResponseEntity<Map<String, String>> updateSummary(
            @PathVariable String id, @RequestBody Map<String, String> body) {
        String summary = body.get("summary");
        if (summary == null || summary.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "summary is required"));
        }
        sessionService.updateSummary(id, summary);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    /** 同步会话到 Memos */
    @PostMapping("/{id}/sync-memos")
    public ResponseEntity<Map<String, String>> syncToMemos(@PathVariable String id) {
        // Phase 3 实现
        return ResponseEntity.ok(Map.of("status", "not_implemented", "message", "Memos sync coming in Phase 3"));
    }
}
