package com.xuzhenwei.agent.technique;

import com.xuzhenwei.agent.agent.AgentEvent;
import com.xuzhenwei.agent.agent.ResponseRefiner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 技法执行器 — 统一调用入口，执行后自动精炼输出
 */
@Service
public class TechniqueExecutor {

    private static final Logger log = LoggerFactory.getLogger(TechniqueExecutor.class);

    private final TechniqueRegistry registry;
    private final ResponseRefiner refiner;

    public TechniqueExecutor(TechniqueRegistry registry, ResponseRefiner refiner) {
        this.registry = registry;
        this.refiner = refiner;
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
                // 全部收集完毕后，精炼整理
                .concatWith(refineAndEmit(rawOutput.toString(), topic))
                .onErrorResume(e -> {
                    log.error("技法执行异常", e);
                    return Flux.just(AgentEvent.error(e.getMessage()));
                });
    }

    /**
     * 将原始输出送去精炼，返回精炼结果事件
     */
    private Flux<AgentEvent> refineAndEmit(String rawContent, String userTopic) {
        if (rawContent.isBlank()) return Flux.empty();

        return Flux.create(sink -> {
            try {
                // 先发一个"正在整理"的提示
                sink.next(AgentEvent.stepStart(99, "🧹 正在整理排版...", "refine"));

                // 调用精炼器
                String refined = refiner.refine(rawContent, userTopic);

                // 发送精炼后的内容
                for (String line : refined.split("\n")) {
                    sink.next(AgentEvent.stepContent(99, line + "\n", "refined"));
                }

                sink.next(AgentEvent.stepComplete(99, "refine"));
                sink.complete();

            } catch (Exception e) {
                log.error("精炼失败", e);
                sink.next(AgentEvent.error("整理失败: " + e.getMessage()));
                sink.complete();
            }
        });
    }
}
