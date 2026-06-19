package com.xuzhenwei.agent.technique;

import com.xuzhenwei.agent.agent.AgentEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * 技法执行器 — v2.6 轻量化：跳过LLM精炼，直通输出
 */
@Service
public class TechniqueExecutor {

    private static final Logger log = LoggerFactory.getLogger(TechniqueExecutor.class);

    private final TechniqueRegistry registry;

    public TechniqueExecutor(TechniqueRegistry registry) {
        this.registry = registry;
    }

    /**
     * 执行技法 → 收集原始输出 → 精炼整理 → 返回干净结果
     */
    public Flux<AgentEvent> execute(String techniqueId, String topic, String conversationId) {
        var technique = registry.get(techniqueId);

        if (technique.isEmpty()) {
            return Flux.just(AgentEvent.error(
                    "技法 [%s] 不存在，当前共 %d 个技法可用".formatted(techniqueId, registry.count())));
        }

        log.info("执行技法: {} - {}", technique.get().getId(), technique.get().getName());

        // 收集所有步骤的原始输出
        StringBuilder rawOutput = new StringBuilder();

        return technique.get().execute(topic, conversationId)
                .doOnNext(event -> {
                    // 收集所有内容块
                    if (event.type() == AgentEvent.EventType.STEP_CONTENT && event.content() != null) {
                        rawOutput.append(event.content());
                    }
                })
                .concatWith(Flux.just(AgentEvent.techniqueComplete(techniqueId)))
                // 全部收集完毕后，精炼整理（defer 确保 rawOutput 在订阅时求值，而非组装时）
                .concatWith(Flux.defer(() -> refineAndEmit(rawOutput.toString(), topic)))
                .onErrorResume(e -> {
                    log.error("技法执行异常", e);
                    return Flux.just(AgentEvent.error(e.getMessage()));
                });
    }

    /**
     * 直通输出 — v2.6 优化：跳过LLM精炼，省2K token + 5-10秒
     * 格式化交给前端 Markdown 渲染
     */
    private Flux<AgentEvent> refineAndEmit(String rawContent, String userTopic) {
        if (rawContent.isBlank()) return Flux.empty();

        return Flux.just(
            AgentEvent.stepStart(99, "✅ 分析完成", "refine"),
            AgentEvent.stepContent(99, rawContent, "refined"),
            AgentEvent.stepComplete(99, "refine")
        );
    }
}
