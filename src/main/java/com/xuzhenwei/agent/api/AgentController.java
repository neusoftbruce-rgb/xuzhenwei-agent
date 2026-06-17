package com.xuzhenwei.agent.api;

import com.xuzhenwei.agent.agent.*;
import com.xuzhenwei.agent.technique.TechniqueRegistry;
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

    public AgentController(AgentEngine agentEngine,
                           DeepThinkService deepThinkService,
                           DomainAdvisor domainAdvisor,
                           TokenSaver tokenSaver,
                           TechniqueRegistry techniqueRegistry) {
        this.agentEngine = agentEngine;
        this.deepThinkService = deepThinkService;
        this.domainAdvisor = domainAdvisor;
        this.tokenSaver = tokenSaver;
        this.techniqueRegistry = techniqueRegistry;
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
     * 合并流程：深度推理 + 技法执行 = 1次统一推理
     * 将技法Prompt作为推理框架，DeepSeek Reasoner在技法引导下一步完成
     */
    private Flux<AgentEvent> deepTechniqueThink(String message, String techniqueId, String conversationId) {
        return Flux.create(sink -> {
            try {
                long start = System.currentTimeMillis();
                var tech = techniqueRegistry.get(techniqueId);

                // 构建技法引导的推理Prompt
                StringBuilder techPrompt = new StringBuilder();
                techPrompt.append("请使用以下技法框架来分析用户的问题。\n\n");
                techPrompt.append("【用户问题】\n").append(message).append("\n\n");

                if (tech.isPresent()) {
                    var t = tech.get();
                    techPrompt.append("【技法框架】").append(t.getName()).append("\n");
                    techPrompt.append(t.getDescription()).append("\n\n");
                    // 注入领域知识
                    var matched = domainAdvisor.matchBusiness(message);
                    if (matched.isPresent()) {
                        techPrompt.append("【领域背景】\n").append(domainAdvisor.buildDomainContext()).append("\n\n");
                    }
                    techPrompt.append("请严格按照此技法的思路进行分析，并在输出中体现技法特点。\n");
                } else {
                    techPrompt.append("请对此问题进行深度分析。\n");
                }

                sink.next(AgentEvent.stepStart(0, "🧠 技法深度推理中...", "deepthink-reasoning"));
                sink.next(AgentEvent.stepContent(0, "已加载技法框架，正在执行深度推理...\n\n", "deepthink-reasoning"));

                // 第1步：用推理模型一次性完成深度分析
                var result = deepThinkService.deepThink(techPrompt.toString(), "");

                // 推理过程
                sink.next(AgentEvent.stepContent(0, "### 推理过程\n\n", "deepthink-reasoning"));
                String reasoning = result.reasoning();
                if (reasoning != null) {
                    if (reasoning.length() > 1500) reasoning = reasoning.substring(0, 1500) + "\n\n...(推理过程较长，已截断)";
                    for (String line : reasoning.split("\n")) {
                        sink.next(AgentEvent.stepContent(0, line + "\n", "deepthink-reasoning"));
                    }
                }
                sink.next(AgentEvent.stepComplete(0, "deepthink-reasoning"));

                // 第2步：精炼最终答案
                sink.next(AgentEvent.stepStart(99, "📝 整理输出", "deepthink-final"));
                String finalAnswer = result.finalAnswer();
                if (finalAnswer != null) {
                    for (String line : finalAnswer.split("\n")) {
                        sink.next(AgentEvent.stepContent(99, line + "\n", "deepthink-final"));
                    }
                }
                sink.next(AgentEvent.stepContent(99,
                        "\n\n*⏱ 耗时 %.1f 秒 · 🧠 技法深度推理*".formatted((System.currentTimeMillis() - start) / 1000.0),
                        "deepthink-final"));
                sink.next(AgentEvent.stepComplete(99, "deepthink-final"));
                sink.complete();

            } catch (Exception e) {
                log.error("技法深度推理异常", e);
                sink.next(AgentEvent.error("推理异常: " + e.getMessage()));
                sink.complete();
            }
        });
    }

    public record ThinkRequest(
            String projectId,
            String message,
            String techniqueId,
            Boolean deepThink
    ) {}
}
