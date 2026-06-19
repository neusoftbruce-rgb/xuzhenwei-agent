package com.xuzhenwei.agent.agent;

import com.xuzhenwei.agent.technique.TechniqueExecutor;
import com.xuzhenwei.agent.technique.TechniqueRecommender;
import com.xuzhenwei.agent.technique.TechniqueRegistry;
import com.xuzhenwei.agent.technique.RecommendationDecisionEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import java.util.List;

/**
 * Agent 核心编排引擎 — 徐振伟智能体的"大脑"
 *
 * <p>职责：
 * <ul>
 *   <li>接收用户消息，判断意图（自由对话 vs 技法执行）</li>
 *   <li>如果用户指定了技法 → 调用技法执行器</li>
 *   <li>techniqueId="auto" → 智能推荐并自动执行最佳技法</li>
 *   <li>techniqueId="recipe:xxx" → 执行技法组合配方</li>
 *   <li>如果用户自由对话 → 直接用 ChatClient 进行人机共想</li>
 *   <li>统一管理会话上下文记忆</li>
 * </ul>
 */
@Service
public class AgentEngine {

    private static final Logger log = LoggerFactory.getLogger(AgentEngine.class);

    /** 人机共想的系统角色设定 — v2.4 压缩版 (原~400tokens→~150tokens) */
    private static final String AGENT_SYSTEM_PROMPT = """
            你是徐振伟，农业培训顾问+AI创意策略专家，精通"人机共想"方法论。
            风格：顾问式对话、善于追问、给出有延展空间的方案、用比喻让概念具体。
            原则：创意=新颖性+有用性，不完美的方案激发共创。
            要求：回答简洁有结构，关键结论加粗，用要点列表。800字以内。
            """;

    private final ChatClient chatClient;
    private final ConversationManager conversationManager;
    private final TechniqueRegistry techniqueRegistry;
    private final TechniqueExecutor techniqueExecutor;
    private final TechniqueRecommender techniqueRecommender;
    private final DomainAdvisor domainAdvisor;
    private final RecommendationDecisionEngine decisionEngine;

    public AgentEngine(ChatClient chatClient,
                       ConversationManager conversationManager,
                       TechniqueRegistry techniqueRegistry,
                       TechniqueExecutor techniqueExecutor,
                       TechniqueRecommender techniqueRecommender,
                       DomainAdvisor domainAdvisor,
                       RecommendationDecisionEngine decisionEngine) {
        this.chatClient = chatClient;
        this.conversationManager = conversationManager;
        this.techniqueRegistry = techniqueRegistry;
        this.techniqueExecutor = techniqueExecutor;
        this.techniqueRecommender = techniqueRecommender;
        this.domainAdvisor = domainAdvisor;
        this.decisionEngine = decisionEngine;
    }

    /**
     * 执行一次"人机共想"对话
     *
     * @param conversationId 会话 ID
     * @param userMessage    用户消息
     * @param techniqueId    用户选择的技法（可为 null）
     * @return 流式 AgentEvent
     */
    public Flux<AgentEvent> think(String conversationId, String userMessage, String techniqueId) {

        // 保存用户消息到会话历史
        conversationManager.append(conversationId, "用户", userMessage);

        // 预分析复杂度（方法1+方法6 共用）
        var analysis = decisionEngine.analyze(userMessage);

        // ---- 智能自动模式 ----
        if ("auto".equals(techniqueId)) {
            return autoRecommendAndExecute(conversationId, userMessage);
        }

        // ---- 配方模式（技法组合链） ----
        if (techniqueId != null && techniqueId.startsWith("recipe:")) {
            String recipeName = techniqueId.substring(7);
            return withVerification(
                    executeRecipe(recipeName, userMessage, conversationId),
                    userMessage, conversationId, analysis.complexity());
        }

        // ---- 指定技法模式 ----
        if (techniqueId != null && !techniqueId.isBlank()) {
            return withVerification(
                    techniqueExecutor.execute(techniqueId, userMessage, conversationId),
                    userMessage, conversationId, analysis.complexity());
        }

        // ---- 自由对话模式 ----
        return freeConversation(conversationId, userMessage);
    }

    /**
     * 智能推荐并自动执行最佳技法 —— v3.0 置信度分层路由
     *
     * <p>路由规则 (方法3)：
     * <ul>
     *   <li>Top-1 置信度 ≥ 90% → 静默自动执行，不展示卡片</li>
     *   <li>Top-1 置信度 80-89% → 展示推荐 + 标注"高置信"，自动执行</li>
     *   <li>Top-1 置信度 50-79% → 展示推荐卡片，等待用户确认</li>
     *   <li>Top-1 置信度 < 50% → 触发 LLM 深度推荐复核</li>
     * </ul>
     */
    private Flux<AgentEvent> autoRecommendAndExecute(String conversationId, String userMessage) {
        var recommendation = techniqueRecommender.recommend(userMessage);
        var suggestions = recommendation.suggestions();
        var topConfidence = suggestions.isEmpty() ? 0.0 : suggestions.get(0).confidence();
        var analysisContext = recommendation.complexity();

        // ═══════════ 路由决策 ═══════════
        // 路径A: ≥ 90% → 静默自动执行
        if (topConfidence >= 0.90) {
            var best = suggestions.get(0);
            log.info("高置信度自动执行: {} ({}%)", best.techniqueId(), (int)(topConfidence*100));
            return Flux.just(AgentEvent.stepContent(0,
                    "🎯 自动匹配「**%s**」(置信度 %.0f%%)，直接为你分析：\n\n".formatted(
                            best.techniqueName(), topConfidence * 100), "auto-routing"))
                    .concatWith(techniqueExecutor.execute(best.techniqueId(), userMessage, conversationId));
        }

        // 路径B: 80-89% → 展示推荐 + 自动执行
        if (topConfidence >= 0.80) {
            var best = suggestions.get(0);
            Flux<AgentEvent> header = Flux.just(
                    AgentEvent.stepContent(0,
                            "🧠 分析完成，推荐以下技法：\n\n", "auto")
            );
            header = appendSuggestionCards(header, recommendation);
            header = header.concatWith(Flux.just(AgentEvent.stepContent(0,
                    "\n⚡ 高置信度匹配 (%.0f%%)，自动执行「**%s**」...\n\n".formatted(
                            topConfidence * 100, best.techniqueName()), "auto-routing")));
            return header.concatWith(
                    techniqueExecutor.execute(best.techniqueId(), userMessage, conversationId));
        }

        // 路径C: 50-79% → 推荐卡片（等用户在前端确认）
        if (topConfidence >= 0.50) {
            Flux<AgentEvent> header = Flux.just(
                    AgentEvent.stepContent(0,
                            "🧠 分析完成，推荐以下技法（点击卡片执行）：\n\n", "auto")
            );
            header = appendSuggestionCards(header, recommendation);
            // 配方匹配提示
            if (recommendation.recipe() != null) {
                var recipe = recommendation.recipe();
                header = header.concatWith(Flux.just(
                        AgentEvent.stepContent(0,
                                "\n💡 也可使用配方「**%s**」一站式解决\n".formatted(recipe.name()),
                                "auto-recipe-hint")
                ));
            }
            return header;
        }

        // 路径D: < 50% → 低置信度，触发 LLM 深度推荐或回退
        log.info("低置信度({}%)，触发LLM深度推荐", (int)(topConfidence*100));
        if (!suggestions.isEmpty()) {
            var best = suggestions.get(0);
            return Flux.just(
                    AgentEvent.stepContent(0,
                            "🤔 你的问题比较复杂，让我仔细分析一下...\n\n", "auto-low-confidence"),
                    AgentEvent.stepContent(0,
                            "💡 初步判断：「**%s**」(%.0f%%) 可能适合，正在深度分析确认...\n\n"
                                    .formatted(best.techniqueName(), topConfidence * 100),
                            "auto-low-confidence")
            ).concatWith(techniqueExecutor.execute(best.techniqueId(), userMessage, conversationId));
        }

        // 终极回退
        return techniqueExecutor.execute("003", userMessage, conversationId);
    }

    /** 将推荐建议格式化为卡片文本（供 SSE 流输出） */
    private Flux<AgentEvent> appendSuggestionCards(Flux<AgentEvent> chain,
                                                    TechniqueRecommender.RecommendationResult rec) {
        if (rec.recipe() != null) {
            var recipe = rec.recipe();
            chain = chain.concatWith(Flux.just(
                    AgentEvent.stepContent(0,
                            "📋 **配方：「%s」**\n> %s\n\n".formatted(recipe.name(), recipe.description()),
                            "auto")
            ));
        }
        for (var s : rec.suggestions()) {
            chain = chain.concatWith(Flux.just(
                    AgentEvent.stepContent(0,
                            "- **[%s] %s** (%.0f%%) — %s\n".formatted(
                                    s.techniqueId(), s.techniqueName(),
                                    s.confidence() * 100, s.reason()),
                            "auto")
            ));
        }
        return chain;
    }

    /**
     * 执行技法组合配方——多条技法串联执行
     */
    private Flux<AgentEvent> executeRecipe(String recipeName, String userMessage, String conversationId) {
        // 查找匹配的配方
        var recipes = List.of(
                // ---- 原8条配方 ----
                new RecipeDef("创业验证套餐", List.of("002", "026", "034", "031")),
                new RecipeDef("内容创作套餐", List.of("007", "024", "036")),
                new RecipeDef("产品定价套餐", List.of("034", "050", "027")),
                new RecipeDef("客户获取套餐", List.of("035", "048", "053")),
                new RecipeDef("战略规划套餐", List.of("005", "015", "030", "038")),
                new RecipeDef("提案打磨套餐", List.of("025", "031", "033")),
                new RecipeDef("农业品牌套餐", List.of("001", "005", "028", "036")),
                new RecipeDef("深度诊断套餐", List.of("040", "044", "045", "030")),
                // ---- 5条农业混合工作流 (v4.0) ----
                new RecipeDef("农技推广：发现翻译传播", List.of("054", "TIPS-44", "TIPS-07", "036")),
                new RecipeDef("课程开发：策划验证迭代", List.of("025", "029", "037", "027", "TIPS-33")),
                new RecipeDef("农场诊断：把脉开方跟诊", List.of("005", "040", "022", "038", "TIPS-19")),
                new RecipeDef("农业品牌：洞察定位内容", List.of("047", "048", "051", "023", "001")),
                new RecipeDef("学员养成：学练考用闭环", List.of("TIPS-14", "TIPS-24", "TIPS-27", "037"))
        );

        var matched = recipes.stream()
                .filter(r -> r.name.equals(recipeName))
                .findFirst();

        if (matched.isEmpty()) {
            return Flux.just(AgentEvent.error("未找到配方: " + recipeName));
        }

        var recipe = matched.get();

        // 串联执行所有技法
        Flux<AgentEvent> chain = Flux.empty();
        for (int i = 0; i < recipe.techniqueIds.size(); i++) {
            String tid = recipe.techniqueIds.get(i);
            var tech = techniqueRegistry.get(tid);
            String label = tech.map(t -> "[%s] %s".formatted(t.getId(), t.getName())).orElse(tid);

            chain = chain.concatWith(
                    Flux.just(AgentEvent.stepContent(0,
                            "\n---\n### 📌 配方第%d步：%s\n\n".formatted(i + 1, label), "recipe"))
            );
            chain = chain.concatWith(
                    techniqueExecutor.execute(tid, userMessage, conversationId)
            );
        }

        chain = chain.concatWith(
                Flux.just(AgentEvent.stepContent(0,
                        "\n---\n✅ 配方「%s」全部%d步执行完毕！\n".formatted(recipeName, recipe.techniqueIds.size()), "recipe"))
        );

        return chain;
    }

    /**
     * 自由对话模式
     */
    private Flux<AgentEvent> freeConversation(String conversationId, String userMessage) {
        return Flux.create(sink -> {
            try {
                String history = conversationManager.formatHistory(conversationId);

                String domainContext = "";
                var matched = domainAdvisor.matchBusiness(userMessage);
                if (matched.isPresent()) {
                    domainContext = domainAdvisor.buildDomainContext();
                }

                String systemPrompt = domainContext.isEmpty()
                        ? AGENT_SYSTEM_PROMPT
                        : AGENT_SYSTEM_PROMPT + "\n\n" + domainContext;

                var fullPrompt = history.isEmpty()
                        ? userMessage
                        : history + "\n## 当前问题\n" + userMessage;

                var stream = chatClient.prompt()
                        .system(systemPrompt)
                        .user(fullPrompt)
                        .stream()
                        .content();

                StringBuilder fullResponse = new StringBuilder();

                stream.doOnComplete(() -> {
                            conversationManager.append(conversationId, "徐振伟", fullResponse.toString());
                            sink.complete();
                        })
                        .doOnError(e -> {
                            log.error("自由对话异常", e);
                            sink.next(AgentEvent.error("生成异常: " + e.getMessage()));
                            sink.complete();
                        })
                        .subscribe(chunk -> {
                            fullResponse.append(chunk);
                            sink.next(AgentEvent.stepContent(0, chunk, "free"));
                        });

            } catch (Exception e) {
                log.error("AgentEngine error", e);
                sink.next(AgentEvent.error(e.getMessage()));
                sink.complete();
            }
        });
    }

    private record RecipeDef(String name, List<String> techniqueIds) {}

    // ═══════════════════════════════════════════════════════════
    // 方法6: 结果交叉验证
    // ═══════════════════════════════════════════════════════════

    /**
     * 在技法执行后追加轻量验证步骤。
     *
     * <p>触发条件：复杂度 ≥ COMPLEX_MULTI 且不是自由对话模式。</p>
     * <p>验证技法：默认用 031（魔鬼审阅），如果输出涉及财务则用 027（可行性打分）。</p>
     */
    private Flux<AgentEvent> withVerification(Flux<AgentEvent> mainFlux,
                                               String userMessage,
                                               String conversationId,
                                               RecommendationDecisionEngine.ComplexityLevel complexity) {
        if (complexity == null
                || complexity == RecommendationDecisionEngine.ComplexityLevel.SIMPLE_FACTUAL
                || complexity == RecommendationDecisionEngine.ComplexityLevel.SINGLE_DOMAIN) {
            return mainFlux; // 简单问题跳过验证
        }

        // 选择验证技法：财务相关用027，否则用031
        String verifyTechId = userMessage.toLowerCase().matches(".*(钱|财务|成本|定价|收入|利润|赚).*")
                ? "027" : "031";

        var verifyTech = techniqueRegistry.get(verifyTechId);
        if (verifyTech.isEmpty()) return mainFlux;

        return mainFlux.concatWith(
                Flux.just(AgentEvent.stepContent(0,
                        "\n\n---\n### 🔍 自动验证 (%s)\n\n".formatted(verifyTech.get().getName()),
                        "verify"))
                        .concatWith(techniqueExecutor.execute(verifyTechId,
                                "请对以上分析结果进行审阅，指出：\n1. 逻辑漏洞或未考虑的因素\n2. 可以做得更好的地方\n3. 一句话改进建议\n\n原始问题：" + userMessage,
                                conversationId + "_verify"))
                        .concatWith(Flux.just(AgentEvent.stepContent(0,
                                "\n> *🤖 自动交叉验证完成 — 方法031/027*",
                                "verify")))
        );
    }

    /**
     * 获取可用技法简要信息
     */
    public String getTechniquesSummary() {
        var sb = new StringBuilder("当前可用技法：\n\n");
        var techniques = techniqueRegistry.getAll();
        for (var t : techniques) {
            sb.append("**%s** [%s] — %s\n".formatted(t.getId(), t.getName(), t.getDescription()));
        }
        sb.append("\n共 %d 条技法。输入技法编号即可调用。".formatted(techniques.size()));
        return sb.toString();
    }
}
