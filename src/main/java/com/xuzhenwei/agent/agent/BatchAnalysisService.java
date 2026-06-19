package com.xuzhenwei.agent.agent;

import com.xuzhenwei.agent.technique.TechniqueExecutor;
import com.xuzhenwei.agent.technique.TechniqueRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.*;

/**
 * 批量分析管道 — v2.6 轻量化 + 自动重试
 *
 * <p>核心流程：
 * <ol>
 *   <li>逐个执行技法（60秒超时）</li>
 *   <li>超时的自动重试（不限轮数，直到全部完成），无需人工干预</li>
 *   <li>全部完成后，生成汇总报告</li>
 * </ol>
 *
 * <p>Token：0（汇总模板拼装，不调LLM）</p>
 */
@Service
public class BatchAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(BatchAnalysisService.class);

    private final TechniqueRegistry registry;
    private final TechniqueExecutor executor;

    /** 单技法超时 */
    private static final Duration TECHNIQUE_TIMEOUT = Duration.ofSeconds(60);

    public BatchAnalysisService(TechniqueRegistry registry,
                                TechniqueExecutor executor) {
        this.registry = registry;
        this.executor = executor;
    }

    /**
     * 批量执行技法分析 — 全部完成后才出报告
     */
    public Flux<AgentEvent> executeBatch(String userMessage,
                                          List<String> techniqueIds,
                                          String conversationId) {
        if (techniqueIds == null || techniqueIds.isEmpty()) {
            return Flux.just(AgentEvent.error("请至少选择一个技法"));
        }

        List<String> validIds = techniqueIds.stream()
                .filter(id -> registry.get(id).isPresent())
                .toList();

        if (validIds.isEmpty()) {
            return Flux.just(AgentEvent.error("没有找到有效的技法"));
        }

        log.info("批量分析启动: {} 个技法 (v2.6 自动重试模式)", validIds.size());

        // 状态追踪
        Map<String, String> techNames = new LinkedHashMap<>();   // id → name
        Map<String, String> results = new LinkedHashMap<>();     // id → 完整输出（成功的）
        Map<String, String> partials = new LinkedHashMap<>();    // id → 超时时已生成的部分内容
        Set<String> completed = new LinkedHashSet<>();
        Set<String> failed = new LinkedHashSet<>();

        for (String tid : validIds) {
            var tech = registry.get(tid);
            tech.ifPresent(t -> techNames.put(tid, "[%s] %s".formatted(t.getId(), t.getName())));
        }

        int total = validIds.size();

        // 构建响应式链：执行一轮 → 检查剩余 → 重试 → ... → 报告
        return executeRound(userMessage, validIds, techNames, results, partials,
                completed, failed, 1, total, conversationId);
    }

    /**
     * 执行一轮：逐个跑未完成的技法，超时的收集起来准备下一轮重试
     */
    private Flux<AgentEvent> executeRound(String userMessage,
                                           List<String> pendingIds,
                                           Map<String, String> techNames,
                                           Map<String, String> results,
                                           Map<String, String> partials,
                                           Set<String> completed,
                                           Set<String> failed,
                                           int round,
                                           int total,
                                           String conversationId) {

        log.info("第{}轮: 待执行{}个技法", round, pendingIds.size());

        // 本轮收集输出（用于摘要）
        StringBuilder[] roundOutput = new StringBuilder[1];
        roundOutput[0] = new StringBuilder();

        // 本轮超时的技法ID
        List<String> roundTimedOut = new ArrayList<>();

        Flux<AgentEvent> chain = Flux.empty();

        if (round > 1) {
            chain = chain.concatWith(Flux.just(
                    AgentEvent.stepContent(0,
                            "\n\n---\n## 🔄 第%d轮自动重试（%d个超时技法）\n\n".formatted(round, pendingIds.size()),
                            "retry-round-header")
            ));
        }

        int doneCount = completed.size();

        for (int i = 0; i < pendingIds.size(); i++) {
            final int stepNum = doneCount + i + 1;
            String tid = pendingIds.get(i);
            String techName = techNames.getOrDefault(tid, tid);

            // 分隔标题
            chain = chain.concatWith(Flux.just(
                    AgentEvent.stepContent(0,
                            "\n\n---\n## 📌 第%d/%d步: %s\n> %s\n\n".formatted(
                                    stepNum, total, techName,
                                    registry.get(tid).map(t -> t.getDescription()).orElse("")),
                            "batch-header")
            ));

            StringBuilder output = new StringBuilder();
            // 如果之前有部分内容，先恢复
            String prevPartial = partials.get(tid);
            if (prevPartial != null && !prevPartial.isBlank()) {
                output.append(prevPartial);
            }

            final String finalTid = tid;
            final String finalTechName = techName;

            Flux<AgentEvent> techResult = executor.execute(tid, userMessage, conversationId)
                    .doOnNext(event -> {
                        if (event.type() == AgentEvent.EventType.STEP_CONTENT && event.content() != null) {
                            output.append(event.content());
                        }
                    })
                    .timeout(TECHNIQUE_TIMEOUT, Flux.defer(() -> {
                        // 超时：保存部分内容，加入本轮重试列表
                        roundTimedOut.add(finalTid);
                        String partial = output.toString();
                        if (!partial.isBlank()) {
                            partials.put(finalTid, partial);
                        }
                        log.warn("技法 {} 超时 (第{}轮)，已保留部分输出", finalTid, round);

                        StringBuilder fb = new StringBuilder();
                        fb.append("\n\n⏰ **%s 超时**（>%ds），已保留已生成内容，将在下一轮自动重试...\n".formatted(
                                finalTechName, TECHNIQUE_TIMEOUT.toSeconds()));
                        if (!partial.isBlank()) {
                            fb.append("\n📋 **当前已生成内容**（重试时会在此基础上继续）：\n\n");
                            fb.append(partial.length() > 500 ? partial.substring(partial.length() - 500) : partial);
                        }
                        return Flux.just(AgentEvent.stepContent(0, fb.toString(), "batch-timeout"));
                    }))
                    .onErrorResume(e -> {
                        if (e instanceof java.util.concurrent.TimeoutException) {
                            return Flux.empty();
                        }
                        log.warn("技法 {} 执行失败: {}", tid, e.getMessage());
                        failed.add(finalTid);
                        return Flux.just(AgentEvent.stepContent(0,
                                "\n❌ 技法 %s 失败: %s\n".formatted(finalTechName, e.getMessage()),
                                "batch-error"));
                    })
                    .doOnComplete(() -> {
                        // 成功完成
                        completed.add(finalTid);
                        results.put(finalTid, output.toString());
                        partials.remove(finalTid); // 清除部分内容
                        log.info("技法 {} 完成 (第{}轮)", finalTid, round);
                    });

            chain = chain.concatWith(techResult);
        }

        // 本轮结束后，检查是否需要重试
        chain = chain.concatWith(Flux.defer(() -> {
            if (!roundTimedOut.isEmpty()) {
                // 还有超时的 → 继续重试，直到全部完成
                log.info("第{}轮结束：{}个超时，启动第{}轮自动重试", round, roundTimedOut.size(), round + 1);
                return Flux.just(AgentEvent.stepContent(0,
                        "\n\n---\n⏰ 第%d轮完成，%d个技法超时，自动启动第%d轮重试...\n\n".formatted(
                                round, roundTimedOut.size(), round + 1),
                        "retry-transition"))
                        .concatWith(executeRound(userMessage, roundTimedOut, techNames,
                                results, partials, completed, failed, round + 1, total, conversationId));
            }

            // 全部完成 → 生成最终报告
            log.info("全部完成：{}个技法全部成功", completed.size());
            return assembleFinalReport(userMessage, techNames, results, completed, failed, total);
        }));

        return chain;
    }

    /**
     * 生成最终报告（模板拼装，0 LLM token）
     */
    private Flux<AgentEvent> assembleFinalReport(String userMessage,
                                                  Map<String, String> techNames,
                                                  Map<String, String> results,
                                                  Set<String> completed,
                                                  Set<String> failed,
                                                  int total) {
        StringBuilder report = new StringBuilder();
        report.append("\n\n---\n## 📊 综合分析报告\n\n");
        report.append("> 共 **").append(total).append("** 个技法：✅ 全部完成 **")
              .append(completed.size()).append("** 个");

        if (!failed.isEmpty()) {
            report.append(" · ⚠️ 异常 **").append(failed.size()).append("** 个");
        }
        report.append("\n\n");

        // 成功的技法摘要
        if (!completed.isEmpty()) {
            report.append("### ✅ 已完成的技法\n\n");
            for (String tid : completed) {
                String name = techNames.getOrDefault(tid, tid);
                String content = results.get(tid);
                if (content != null && !content.isBlank()) {
                    String summary = content.length() > 200
                            ? content.substring(0, 200).replace("\n", " ") + "..."
                            : content.replace("\n", " ");
                    report.append("- **").append(name).append("**: ").append(summary).append("\n");
                }
            }
            report.append("\n");
        }

        // 失败的技法
        if (!failed.isEmpty()) {
            report.append("### ❌ 未完成的技法\n\n");
            for (String tid : failed) {
                String name = techNames.getOrDefault(tid, tid);
                report.append("- ").append(name).append(" (`").append(tid).append("`)\n");
            }
            report.append("\n> 💡 可手动选择以上技法单独执行，或重新加入队列批量执行。\n\n");
        }

        if (completed.isEmpty()) {
            report.append("*（所有技法均未返回有效结果）*\n\n");
        }

        report.append("---\n");
        report.append("💡 **提示**：向上滚动查看每个技法的完整分析内容。\n");

        return Flux.just(
                AgentEvent.stepContent(0, report.toString(), "synthesis-header"),
                AgentEvent.stepContent(0,
                        "\n*✅ 批量分析完成 · %d/%d 成功 · v2.6 自动重试模式*".formatted(completed.size(), total),
                        "synthesis-done")
        );
    }
}
