package com.xuzhenwei.agent.agent;

import com.xuzhenwei.agent.technique.TechniqueExecutor;
import com.xuzhenwei.agent.technique.TechniqueRecommender;
import com.xuzhenwei.agent.technique.TechniqueRegistry;
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

    /** 人机共想的系统角色设定 */
    private static final String AGENT_SYSTEM_PROMPT = """
            你是徐振伟，一位资深农业培训顾问和 AI 创意策略专家。
            你精通"人机共想"（人機共想）方法论，擅长引导用户进行深度创造性思考。

            你的对话风格：
            - 像一位有经验的顾问，不是冰冷的机器
            - 善于追问，引导用户深入思考（通常需要 3-4 轮对话才能触达核心）
            - 当用户给出模糊指令时，你会要求更具体的"全体像"
            - 你给出的方案总是"有缝隙的"——留有让用户补充和延展的空间
            - 适当使用比喻和案例，让抽象概念变得具体

            核心原则：
            - 创意 = 新颖性 + 有用性
            - AI 负责提供"信息雨"，人类负责"重新闪念"
            - 完美的方案会扼杀讨论，不完美的方案激发共创
            """;

    private final ChatClient chatClient;
    private final ConversationManager conversationManager;
    private final TechniqueRegistry techniqueRegistry;
    private final TechniqueExecutor techniqueExecutor;
    private final TechniqueRecommender techniqueRecommender;
    private final DomainAdvisor domainAdvisor;

    public AgentEngine(ChatClient chatClient,
                       ConversationManager conversationManager,
                       TechniqueRegistry techniqueRegistry,
                       TechniqueExecutor techniqueExecutor,
                       TechniqueRecommender techniqueRecommender,
                       DomainAdvisor domainAdvisor) {
        this.chatClient = chatClient;
        this.conversationManager = conversationManager;
        this.techniqueRegistry = techniqueRegistry;
        this.techniqueExecutor = techniqueExecutor;
        this.techniqueRecommender = techniqueRecommender;
        this.domainAdvisor = domainAdvisor;
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

        // ---- 智能自动模式 ----
        if ("auto".equals(techniqueId)) {
            return autoRecommendAndExecute(conversationId, userMessage);
        }

        // ---- 配方模式（技法组合链） ----
        if (techniqueId != null && techniqueId.startsWith("recipe:")) {
            String recipeName = techniqueId.substring(7);
            return executeRecipe(recipeName, userMessage, conversationId);
        }

        // ---- 指定技法模式 ----
        if (techniqueId != null && !techniqueId.isBlank()) {
            return techniqueExecutor.execute(techniqueId, userMessage, conversationId);
        }

        // ---- 自由对话模式 ----
        return freeConversation(conversationId, userMessage);
    }

    /**
     * 智能推荐并自动执行最佳技法
     */
    private Flux<AgentEvent> autoRecommendAndExecute(String conversationId, String userMessage) {
        var recommendation = techniqueRecommender.recommend(userMessage);

        // 先输出推荐说明
        Flux<AgentEvent> header = Flux.just(
                AgentEvent.stepContent(0, "🧠 我分析了你的问题，推荐以下技法：\n\n", "auto")
        );

        if (recommendation.recipe() != null) {
            var recipe = recommendation.recipe();
            header = header.concatWith(Flux.just(
                    AgentEvent.stepContent(0,
                            "📋 **配方：「%s」**\n%s\n\n".formatted(recipe.name(), recipe.description()),
                            "auto")
            ));
        }

        for (var s : recommendation.suggestions()) {
            header = header.concatWith(Flux.just(
                    AgentEvent.stepContent(0,
                            "- **[%s] %s** (%.0f%%) — %s\n".formatted(
                                    s.techniqueId(), s.techniqueName(),
                                    s.confidence() * 100, s.reason()),
                            "auto")
            ));
        }

        // 如果匹配了配方，自动执行配方中的技法链
        if (recommendation.recipe() != null) {
            header = header.concatWith(Flux.just(
                    AgentEvent.stepContent(0, "\n🚀 自动执行配方中...\n\n", "auto")
            ));
            return header.concatWith(
                    executeRecipe(recommendation.recipe().name(), userMessage, conversationId)
            );
        }

        // 否则执行推荐列表中的第一条技法
        if (!recommendation.suggestions().isEmpty()) {
            var best = recommendation.suggestions().get(0);
            header = header.concatWith(Flux.just(
                    AgentEvent.stepContent(0, "\n🚀 自动执行「%s」...\n\n".formatted(best.techniqueName()), "auto")
            ));
            return header.concatWith(
                    techniqueExecutor.execute(best.techniqueId(), userMessage, conversationId)
            );
        }

        // fallback
        return header.concatWith(
                techniqueExecutor.execute("003", userMessage, conversationId)
        );
    }

    /**
     * 执行技法组合配方——多条技法串联执行
     */
    private Flux<AgentEvent> executeRecipe(String recipeName, String userMessage, String conversationId) {
        // 查找匹配的配方
        var recipes = List.of(
                new RecipeDef("创业验证套餐", List.of("002", "026", "034", "031")),
                new RecipeDef("内容创作套餐", List.of("009", "024", "036")),
                new RecipeDef("产品定价套餐", List.of("034", "050", "027")),
                new RecipeDef("客户获取套餐", List.of("035", "048", "053")),
                new RecipeDef("战略规划套餐", List.of("005", "015", "030", "038")),
                new RecipeDef("提案打磨套餐", List.of("025", "031", "033")),
                new RecipeDef("农业品牌套餐", List.of("001", "005", "028", "036")),
                new RecipeDef("深度诊断套餐", List.of("040", "044", "045", "030"))
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
                        .doOnError(sink::error)
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
