package com.xuzhenwei.agent.api;

import com.xuzhenwei.agent.agent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.UUID;

/**
 * Agent 核心 API — 三大模式：自由对话 / 技法执行 / 深度思考
 */
@RestController
@RequestMapping("/api/agent")
@CrossOrigin
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final AgentEngine agentEngine;
    private final DeepThinkService deepThinkService;
    private final DomainAdvisor domainAdvisor;

    public AgentController(AgentEngine agentEngine,
                           DeepThinkService deepThinkService,
                           DomainAdvisor domainAdvisor) {
        this.agentEngine = agentEngine;
        this.deepThinkService = deepThinkService;
        this.domainAdvisor = domainAdvisor;
    }

    /** 主对话接口（SSE流式） */
    @PostMapping(value = "/think", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<AgentEvent> think(@RequestBody ThinkRequest request) {
        String conversationId = request.projectId() != null
                ? request.projectId()
                : UUID.randomUUID().toString();

        // 深度思考模式
        if (request.deepThink() != null && request.deepThink()) {
            return deepThink(request.message(), conversationId);
        }

        return agentEngine.think(conversationId, request.message(), request.techniqueId());
    }

    /** 深度思考接口 */
    @PostMapping(value = "/deep-think", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<AgentEvent> deepThink(@RequestBody ThinkRequest request) {
        return deepThink(request.message(),
                request.projectId() != null ? request.projectId() : UUID.randomUUID().toString());
    }

    private Flux<AgentEvent> deepThink(String message, String conversationId) {
        return Flux.create(sink -> {
            try {
                long start = System.currentTimeMillis();

                // 匹配领域背景
                String context = "";
                var matched = domainAdvisor.matchBusiness(message);
                if (matched.isPresent()) {
                    context = domainAdvisor.buildDomainContext();
                }

                // === 第1步：深度推理 ===
                sink.next(AgentEvent.stepStart(1, "🧠 深度推理中...", "deepthink-reasoning"));
                sink.next(AgentEvent.stepContent(1,
                        "正在使用 DeepSeek Reasoner 进行深度推理分析，约 30-60 秒...\n\n", "deepthink-reasoning"));

                var result = deepThinkService.deepThink(message, context);

                // 显示推理过程
                sink.next(AgentEvent.stepContent(1, "### 📊 推理过程\n\n", "deepthink-reasoning"));
                String reasoning = truncateReasoning(result.reasoning(), 2000);
                for (String line : reasoning.split("\n")) {
                    sink.next(AgentEvent.stepContent(1, line + "\n", "deepthink-reasoning"));
                }
                sink.next(AgentEvent.stepComplete(1, "deepthink-reasoning"));

                // === 第2步：整理答案 ===
                sink.next(AgentEvent.stepStart(2, "📝 整理最终答案...", "deepthink-final"));
                sink.next(AgentEvent.stepContent(2, "\n### 🎯 最终答案\n\n", "deepthink-final"));
                for (String line : result.finalAnswer().split("\n")) {
                    sink.next(AgentEvent.stepContent(2, line + "\n", "deepthink-final"));
                }

                long elapsed = System.currentTimeMillis() - start;
                sink.next(AgentEvent.stepContent(2,
                        "\n\n---\n*深度思考耗时 %.1f 秒*".formatted(elapsed / 1000.0), "deepthink-final"));
                sink.next(AgentEvent.stepComplete(2, "deepthink-final"));
                sink.complete();

            } catch (Exception e) {
                log.error("深度思考异常", e);
                sink.next(AgentEvent.error("深度思考异常: " + e.getMessage()));
                sink.complete();
            }
        });
    }

    /** 截断过长推理文本，保留关键部分 */
    private String truncateReasoning(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "\n\n... *(推理过程较长，已截断显示关键部分)*";
    }

    @GetMapping("/techniques-summary")
    public Map<String, String> techniquesSummary() {
        return Map.of("summary", agentEngine.getTechniquesSummary());
    }

    public record ThinkRequest(
            String projectId,
            String message,
            String techniqueId,
            Boolean deepThink  // 是否启用深度思考
    ) {}
}
