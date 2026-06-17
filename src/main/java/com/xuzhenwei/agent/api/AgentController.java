package com.xuzhenwei.agent.api;

import com.xuzhenwei.agent.agent.*;
import com.xuzhenwei.agent.technique.TechniqueRegistry;
import com.xuzhenwei.agent.technique.TechniqueExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.UUID;

/**
 * Agent 核心 API
 */
@RestController
@RequestMapping("/api/agent")
@CrossOrigin
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final AgentEngine agentEngine;
    private final DeepThinkService deepThinkService;
    private final DomainAdvisor domainAdvisor;
    private final TokenSaver tokenSaver;
    private final TechniqueRegistry techniqueRegistry;
    private final TechniqueExecutor techniqueExecutor;

    public AgentController(AgentEngine agentEngine,
                           DeepThinkService deepThinkService,
                           DomainAdvisor domainAdvisor,
                           TokenSaver tokenSaver,
                           TechniqueRegistry techniqueRegistry,
                           TechniqueExecutor techniqueExecutor) {
        this.agentEngine = agentEngine;
        this.deepThinkService = deepThinkService;
        this.domainAdvisor = domainAdvisor;
        this.tokenSaver = tokenSaver;
        this.techniqueRegistry = techniqueRegistry;
        this.techniqueExecutor = techniqueExecutor;
    }

    @PostMapping(value = "/think", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<AgentEvent> think(@RequestBody ThinkRequest request) {
        String conversationId = request.projectId() != null
                ? request.projectId()
                : UUID.randomUUID().toString();

        String message = request.message();

        // 智能判断是否需要深度思考（省 token）
        boolean useDeepThink;
        if (request.deepThink() != null) {
            useDeepThink = request.deepThink(); // 用户显式指定
        } else {
            useDeepThink = tokenSaver.shouldDeepThink(message); // 智能判断
        }

        // 压缩 Prompt
        String compressed = tokenSaver.compressPrompt(message);

        // 执行技法
        var techniqueId = request.techniqueId();

        // 如果有技法ID，显示使用的技法信息
        if (techniqueId != null && !techniqueId.isBlank()) {
            var tech = techniqueRegistry.get(techniqueId);
            String techLabel = tech.map(t -> "使用技法: [%s] %s".formatted(t.getId(), t.getName()))
                    .orElse("使用技法: " + techniqueId);

            Flux<AgentEvent> header = Flux.just(
                    AgentEvent.stepContent(0, "📌 " + techLabel + "\n\n", "tech-label")
            );

            // 深度思考 + 技法合并为一个流程
            if (useDeepThink) {
                return header.concatWith(deepTechniqueThink(compressed, techniqueId, conversationId));
            }
            return header.concatWith(agentEngine.think(conversationId, compressed, techniqueId));
        }

        if (useDeepThink) {
            return deepThink(compressed, conversationId);
        }

        return agentEngine.think(conversationId, compressed, null);
    }

    @PostMapping(value = "/deep-think", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<AgentEvent> deepThinkEndpoint(@RequestBody ThinkRequest request) {
        return deepThink(request.message(),
                request.projectId() != null ? request.projectId() : UUID.randomUUID().toString());
    }

    private Flux<AgentEvent> deepThink(String message, String conversationId) {
        return Flux.create(sink -> {
            try {
                long start = System.currentTimeMillis();

                String context = "";
                var matched = domainAdvisor.matchBusiness(message);
                if (matched.isPresent()) {
                    context = domainAdvisor.buildDomainContext();
                }

                sink.next(AgentEvent.stepStart(1, "🧠 深度推理中...", "deepthink-reasoning"));
                sink.next(AgentEvent.stepContent(1, "智能判断需要深度分析，使用推理模型...\n\n", "deepthink-reasoning"));

                var result = deepThinkService.deepThink(message, context);

                // 推理过程（截断）
                sink.next(AgentEvent.stepContent(1, "### 推理过程\n\n", "deepthink-reasoning"));
                String reasoning = result.reasoning();
                if (reasoning != null) {
                    if (reasoning.length() > 1500) reasoning = reasoning.substring(0, 1500) + "\n\n...(推理过程较长，已截断)";
                    for (String line : reasoning.split("\n")) {
                        sink.next(AgentEvent.stepContent(1, line + "\n", "deepthink-reasoning"));
                    }
                }
                sink.next(AgentEvent.stepComplete(1, "deepthink-reasoning"));

                // 最终答案
                sink.next(AgentEvent.stepStart(2, "📝 最终答案", "deepthink-final"));
                String finalAnswer = result.finalAnswer();
                if (finalAnswer != null) {
                    for (String line : finalAnswer.split("\n")) {
                        sink.next(AgentEvent.stepContent(2, line + "\n", "deepthink-final"));
                    }
                }
                sink.next(AgentEvent.stepContent(2,
                        "\n\n*⏱ 耗时 %.1f 秒 · 🧠 深度推理模式*".formatted((System.currentTimeMillis() - start) / 1000.0),
                        "deepthink-final"));
                sink.next(AgentEvent.stepComplete(2, "deepthink-final"));
                sink.complete();

            } catch (Exception e) {
                log.error("深度思考异常", e);
                sink.next(AgentEvent.error("深度思考异常: " + e.getMessage()));
                sink.complete();
            }
        });
    }

    @GetMapping("/techniques-summary")
    public Map<String, String> techniquesSummary() {
        return Map.of("summary", agentEngine.getTechniquesSummary());
    }

    /**
     * 分步流式推理：用技法自带的多步骤Prompt，每步立即输出结果
     * 不用DeepSeek Reasoner，直接用Chat模型（快、省token、流式反馈）
     */
    private Flux<AgentEvent> deepTechniqueThink(String message, String techniqueId, String conversationId) {
        var tech = techniqueRegistry.get(techniqueId);
        String techName = tech.map(t -> "[%s] %s".formatted(t.getId(), t.getName())).orElse(techniqueId);
        int totalSteps = tech.map(t -> t.getStepCount()).orElse(1);

        // 注入领域知识到消息中
        String enrichedMessage = message;
        var matched = domainAdvisor.matchBusiness(message);
        if (matched.isPresent()) {
            enrichedMessage = message + "\n\n【领域背景】\n" + domainAdvisor.buildDomainContext();
        }

        // 技法步骤头
        Flux<AgentEvent> header = Flux.just(
            AgentEvent.stepContent(0, "🧠 技法「%s」· 共%d步推理\n\n".formatted(techName, totalSteps), "tech-header")
        );

        // 逐步骤执行，每步立即流式输出
        Flux<AgentEvent> steps = techniqueExecutor.execute(techniqueId, enrichedMessage, conversationId);

        // 最终汇总
        String finalMessage = enrichedMessage;
        Flux<AgentEvent> footer = Flux.just(
            AgentEvent.stepContent(99, "\n\n---\n🎯 技法「%s」· %d步推理完成\n".formatted(techName, totalSteps), "done")
        );

        return header.concatWith(steps).concatWith(footer);
    }

    public record ThinkRequest(
            String projectId,
            String message,
            String techniqueId,
            Boolean deepThink
    ) {}
}
