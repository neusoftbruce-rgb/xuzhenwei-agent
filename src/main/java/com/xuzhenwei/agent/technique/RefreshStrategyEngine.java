package com.xuzhenwei.agent.technique;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 多策略"换一批"刷新引擎 — v3.2
 *
 * <p>5 种策略循环切换，确保每次"换一批"给用户不同的技法组合：
 * <ol>
 *   <li>🌐 换角度 — 从不同分类强制选技法</li>
 *   <li>📐 换深度 — 切换技法难度/颗粒度</li>
 *   <li>🔀 换组合 — 重新生成MECE计划，不同槽位分配</li>
 *   <li>🧠 AI重推 — LLM从不同视角推荐</li>
 *   <li>⚡ 对立思维 — 故意推荐相反阶段的技法</li>
 * </ol>
 *
 * <p>设计参考：
 * <ul>
 *   <li>贝叶斯引导多样性采样 (arXiv:2506.21617, 2025)</li>
 *   <li>时间感知探索 (UFSCar, 2025)</li>
 * </ul>
 *
 * @since 2026-06-20
 */
@Service
public class RefreshStrategyEngine {

    private static final Logger log = LoggerFactory.getLogger(RefreshStrategyEngine.class);

    /** 5 种策略 */
    public enum Strategy {
        CHANGE_ANGLE("🌐 换角度", "从不同分类选技法，换个视角看问题"),
        CHANGE_DEPTH("📐 换深度", "切换技法难度/颗粒度，深挖或广览"),
        CHANGE_COMBO("🔀 换组合", "重新分配MECE槽位，生成不同组合"),
        AI_RERANK("🧠 AI重推", "让AI从关键词遗漏的角度推荐"),
        CONTRASTIVE("⚡ 对立思维", "推荐相反的思考路径，辩证分析");

        private final String label;
        private final String description;
        Strategy(String l, String d) { this.label = l; this.description = d; }
        public String getLabel() { return label; }
        public String getDescription() { return description; }
    }

    private final ChatClient chatClient;
    private final TechniqueRegistry registry;
    private final MeceCoverageEngine meceEngine;

    public RefreshStrategyEngine(ChatClient chatClient, TechniqueRegistry registry,
                                  MeceCoverageEngine meceEngine) {
        this.chatClient = chatClient;
        this.registry = registry;
        this.meceEngine = meceEngine;
    }

    // ═══════════════════════════════════════════════════════════
    // 核心API
    // ═══════════════════════════════════════════════════════════

    /**
     * 刷新上下文 — 携带用户已看过的所有技法信息
     */
    public record RefreshContext(
            String userInput,                           // 原始用户问题
            int round,                                  // 第几轮刷新 (1-based)
            Set<String> seenTechniqueIds,               // 用户已经看过的技法ID
            Set<String> previousCategories,              // 上一轮推荐的分类
            MeceCoverageEngine.MecePlan previousMecePlan, // 上一轮的MECE计划
            RecommendationDecisionEngine.AnalysisContext analysisContext // 意图分析
    ) {}

    /**
     * 刷新结果
     */
    public record RefreshResult(
            Strategy strategy,                          // 本轮使用的策略
            List<TechniqueRecommender.TechniqueSuggestion> suggestions,
            String explanation,
            MeceCoverageEngine.MecePlan mecePlan
    ) {}

    /**
     * 根据刷新轮次选择策略并执行。
     *
     * @param ctx 刷新上下文
     * @return 刷新结果
     */
    public RefreshResult refresh(RefreshContext ctx) {
        // 按轮次循环选择策略
        Strategy strategy = selectStrategy(ctx.round);

        log.info("换一批 第{}轮 → 策略: {}", ctx.round, strategy.getLabel());

        return switch (strategy) {
            case CHANGE_ANGLE -> changeAngle(ctx);
            case CHANGE_DEPTH -> changeDepth(ctx);
            case CHANGE_COMBO -> changeCombo(ctx);
            case AI_RERANK -> aiRerank(ctx);
            case CONTRASTIVE -> contrastive(ctx);
        };
    }

    /** 循环选择策略：按轮次模5分配 */
    private Strategy selectStrategy(int round) {
        return Strategy.values()[(round - 1) % Strategy.values().length];
    }

    /** 获取某轮的建议策略标签（用于前端预告"下次是什么"） */
    public Strategy nextStrategy(int round) {
        return Strategy.values()[round % Strategy.values().length];
    }

    // ═══════════════════════════════════════════════════════════
    // 策略实现
    // ═══════════════════════════════════════════════════════════

    /**
     * 策略1: 换角度 — 排除上一轮占比最高的分类，从剩余分类中选技法。
     */
    private RefreshResult changeAngle(RefreshContext ctx) {
        // 找出上一轮占比最高的分类
        String dominantCategory = findDominantCategory(ctx.previousCategories());
        log.debug("换角度: 排除分类={}", dominantCategory);

        // 从非主导分类中选技法
        var candidates = registry.getAll().stream()
                .filter(t -> !t.getCategory().equals(dominantCategory))
                .filter(t -> !ctx.seenTechniqueIds().contains(t.getId()))
                .toList();

        var suggestions = buildSuggestions(candidates, ctx.userInput(), Math.min(6, candidates.size()), ctx.seenTechniqueIds());
        return new RefreshResult(Strategy.CHANGE_ANGLE, suggestions,
                "🌐 换角度 — 排除「" + dominantCategory + "」，从其他分类推荐",
                null);
    }

    /**
     * 策略2: 换深度 — 切换技法难度/颗粒度，stepCount 少的换多的或反之。
     */
    private RefreshResult changeDepth(RefreshContext ctx) {
        // 计算上一轮的平均步骤数
        var prevTechs = ctx.seenTechniqueIds().stream()
                .map(registry::get)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();
        double avgSteps = prevTechs.stream().mapToInt(t -> t.getStepCount()).average().orElse(2.0);

        // 本轮选相反颗粒度
        boolean preferLong = avgSteps < 2.5; // 上轮短→本轮长

        var candidates = registry.getAll().stream()
                .filter(t -> !ctx.seenTechniqueIds().contains(t.getId()))
                .filter(t -> preferLong ? t.getStepCount() >= 3 : t.getStepCount() <= 2)
                .toList();

        String label = preferLong ? "深度聚焦(多步骤)" : "快速轻量(少步骤)";
        var suggestions = buildSuggestions(candidates, ctx.userInput(),
                Math.min(6, candidates.size()), ctx.seenTechniqueIds());
        return new RefreshResult(Strategy.CHANGE_DEPTH, suggestions,
                "📐 换深度 — 上轮均" + String.format("%.1f", avgSteps) + "步 → 本轮" + label,
                null);
    }

    /**
     * 策略3: 换组合 — 重新生成MECE计划+随机扰动，切换主导阶段。
     */
    private RefreshResult changeCombo(RefreshContext ctx) {
        // 重新生成MECE计划（不依赖前端传plan，后端自给自足）
        var freshPlan = meceEngine.plan(ctx.analysisContext(), ctx.userInput());
        int totalSlots = freshPlan.totalSlots();
        if (totalSlots <= 0) totalSlots = 5; // 兜底

        Map<MeceCoverageEngine.MecePhase, Double> perturbed = new LinkedHashMap<>();
        var rnd = new Random();

        // 每个阶段加 ±0.15 随机扰动
        for (var phase : MeceCoverageEngine.MecePhase.values()) {
            double original = freshPlan.demandStrength().getOrDefault(phase, 0.5);
            double perturbation = (rnd.nextDouble() - 0.5) * 0.30;
            perturbed.put(phase, Math.max(0.1, Math.min(1.0, original + perturbation)));
        }

        // 切换主导阶段：削弱原主导，加强对立阶段
        var oldDominant = freshPlan.demandStrength().entrySet().stream()
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        if (oldDominant != null) {
            perturbed.put(oldDominant, Math.max(0.1, perturbed.get(oldDominant) - 0.25));
            var opposite = getOppositePhase(oldDominant);
            if (opposite != null) {
                perturbed.put(opposite, Math.min(1.0, perturbed.getOrDefault(opposite, 0.5) + 0.25));
            }
        }

        var alloc = allocateSlotsSimple(perturbed, totalSlots);

        // 按新槽位从 Registry 选技法
        List<TechniqueRecommender.TechniqueSuggestion> all = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>(ctx.seenTechniqueIds());

        for (var phase : MeceCoverageEngine.MecePhase.values()) {
            int slots = alloc.getOrDefault(phase, 0);
            if (slots <= 0) continue;
            var categories = MeceCoverageEngine.getPhaseCategories(phase);
            var pool = registry.getAll().stream()
                    .filter(t -> categories.contains(t.getCategory()))
                    .filter(t -> !used.contains(t.getId()))
                    .toList();
            var phaseSuggestions = buildSuggestions(pool, ctx.userInput(), slots, ctx.seenTechniqueIds());
            phaseSuggestions.forEach(s -> used.add(s.techniqueId()));
            all.addAll(phaseSuggestions);
        }

        return new RefreshResult(Strategy.CHANGE_COMBO, all,
                "🔀 换组合 — 重新分配MECE槽位，不同技法组合",
                new MeceCoverageEngine.MecePlan(perturbed, alloc, Map.of(), totalSlots, "扰动MECE", false));
    }

    /**
     * 策略4: AI重推 — 用 LLM 从不同角度推荐技法 (~300 tokens)。
     */
    private RefreshResult aiRerank(RefreshContext ctx) {
        try {
            // 构建已看技法列表
            StringBuilder seenList = new StringBuilder();
            for (String id : ctx.seenTechniqueIds()) {
                var tech = registry.get(id);
                tech.ifPresent(t -> seenList.append("[").append(t.getId()).append("] ")
                        .append(t.getName()).append(" — ").append(t.getDescription()).append("\n"));
            }

            // 构建候选池（排除已看的）
            var candidates = registry.getAll().stream()
                    .filter(t -> !ctx.seenTechniqueIds().contains(t.getId()))
                    .limit(40).toList();
            StringBuilder candList = new StringBuilder();
            for (var t : candidates) {
                candList.append("[").append(t.getId()).append("] ")
                        .append(t.getName()).append(" — ").append(t.getDescription()).append("\n");
            }

            String prompt = """
                    用户问题：「%s」

                    已经推荐过这些技法：
                    %s

                    请从以下候选技法中，选出3-5条"从不同角度切入"但同样有用的技法。
                    优先选那些"上一个推荐遗漏了但换个角度就很有价值"的技法。

                    候选技法：
                    %s

                    严格按JSON格式输出：
                    {"picks":[{"id":"001","reason":"一句话理由(强调不同角度)"}]}
                    """.formatted(ctx.userInput(), seenList.toString(), candList.toString());

            String response = chatClient.prompt()
                    .user(prompt)
                    .options(OpenAiChatOptions.builder()
                            .model("glm-5.2")
                            .maxTokens(300)
                            .temperature(0.4)
                            .build())
                    .call()
                    .content();

            var suggestions = parseAiPicks(response);
            if (suggestions.isEmpty()) {
                return changeAngle(ctx); // fallback
            }
            return new RefreshResult(Strategy.AI_RERANK, suggestions,
                    "🧠 AI重推 — 从不同视角发现了" + suggestions.size() + "条新技法",
                    null);

        } catch (Exception e) {
            log.warn("AI重推失败，回退换角度: {}", e.getMessage());
            return changeAngle(ctx);
        }
    }

    /**
     * 策略5: 对立思维 — 故意推荐相反阶段的技法。
     */
    private RefreshResult contrastive(RefreshContext ctx) {
        // 重新生成MECE计划来确定当前主导阶段（不依赖前端传plan）
        var freshPlan = meceEngine.plan(ctx.analysisContext(), ctx.userInput());
        MeceCoverageEngine.MecePhase dominantPhase = null;
        if (freshPlan.totalSlots() > 0) {
            dominantPhase = freshPlan.demandStrength().entrySet().stream()
                    .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        }
        var opposite = getOppositePhase(dominantPhase);

        var categories = opposite != null
                ? MeceCoverageEngine.getPhaseCategories(opposite)
                : List.of("找到思考切入点");

        var candidates = registry.getAll().stream()
                .filter(t -> categories.contains(t.getCategory()))
                .filter(t -> !ctx.seenTechniqueIds().contains(t.getId()))
                .toList();

        String oppositeLabel = opposite != null ? opposite.getLabel() : "诊断分析";
        var suggestions = buildSuggestions(candidates, ctx.userInput(),
                Math.min(6, candidates.size()), ctx.seenTechniqueIds());
        return new RefreshResult(Strategy.CONTRASTIVE, suggestions,
                "⚡ 对立思维 — 上轮偏" +
                        (dominantPhase != null ? dominantPhase.getLabel() : "发散") +
                        " → 本轮侧重" + oppositeLabel,
                null);
    }

    // ═══════════════════════════════════════════════════════════
    // 辅助方法
    // ═══════════════════════════════════════════════════════════

    private String findDominantCategory(Set<String> categories) {
        if (categories == null || categories.isEmpty()) return "快速产生灵感";
        Map<String, Integer> counts = new HashMap<>();
        for (String c : categories) counts.merge(c, 1, Integer::sum);
        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse("快速产生灵感");
    }

    private MeceCoverageEngine.MecePhase getOppositePhase(MeceCoverageEngine.MecePhase phase) {
        if (phase == null) return MeceCoverageEngine.MecePhase.VERIFICATION;
        return switch (phase) {
            case IDEATION -> MeceCoverageEngine.MecePhase.VERIFICATION;
            case REFINEMENT -> MeceCoverageEngine.MecePhase.IDEATION;
            case VERIFICATION -> MeceCoverageEngine.MecePhase.IDEATION;
            case EXECUTION -> MeceCoverageEngine.MecePhase.REFINEMENT;
        };
    }

    /**
     * 用 MMR (Maximal Marginal Relevance) 评分选技法。
     * Score = λ·relevance - (1-λ)·max_sim_to_seen，确保每次刷新与已看技法不同。
     */
    private List<TechniqueRecommender.TechniqueSuggestion> buildSuggestions(
            List<com.xuzhenwei.agent.technique.Technique> pool, String query, int count,
            Set<String> seenIds) {
        final double LAMBDA = 0.6;

        // 归一化分母
        final double rawMax = pool.stream()
                .mapToDouble(t -> countKeywordOverlap(t.getName() + t.getDescription(), query))
                .max().orElse(1.0);
        final double normFactor = rawMax == 0 ? 1.0 : rawMax;

        return pool.stream()
                .map(t -> {
                    double relevance = countKeywordOverlap(t.getName() + t.getDescription(), query) / normFactor;
                    // 多样性惩罚：与任何已看技法同分类则扣分
                    double maxSim = 0;
                    if (seenIds != null) {
                        for (String sid : seenIds) {
                            var seen = registry.get(sid);
                            if (seen.isPresent() && seen.get().getCategory().equals(t.getCategory())) {
                                maxSim = 1.0;
                                break;
                            }
                        }
                    }
                    double mmr = LAMBDA * relevance - (1 - LAMBDA) * maxSim;
                    double confidence = Math.max(0.30, Math.min(0.78, 0.35 + (mmr + 0.4) * 0.50));
                    return new TechniqueRecommender.TechniqueSuggestion(
                            t.getId(), t.getName(), confidence, "🔄 策略刷新");
                })
                .sorted((a, b) -> Double.compare(b.confidence(), a.confidence()))
                .limit(count)
                .toList();
    }

    private int countKeywordOverlap(String text, String query) {
        int count = 0;
        String lower = text.toLowerCase();
        String q = query.toLowerCase();
        for (int len = 3; len >= 2; len--) {
            for (int i = 0; i + len <= q.length(); i++) {
                if (lower.contains(q.substring(i, i + len))) count++;
            }
        }
        return count;
    }

    private Map<MeceCoverageEngine.MecePhase, Integer> allocateSlotsSimple(
            Map<MeceCoverageEngine.MecePhase, Double> demand, int total) {
        Map<MeceCoverageEngine.MecePhase, Integer> alloc = new LinkedHashMap<>();
        double sum = demand.values().stream().mapToDouble(Double::doubleValue).sum();
        if (sum == 0) return alloc;
        int assigned = 0;
        for (var e : demand.entrySet()) {
            int slots = (int) Math.round(total * e.getValue() / sum);
            if (e.getValue() > 0.35 && slots < 1) slots = 1;
            alloc.put(e.getKey(), slots);
            assigned += slots;
        }
        return alloc;
    }

    /** 解析 AI 重推的 JSON 响应 */
    private List<TechniqueRecommender.TechniqueSuggestion> parseAiPicks(String json) {
        List<TechniqueRecommender.TechniqueSuggestion> result = new ArrayList<>();
        try {
            var objPattern = Pattern.compile("\\{[^}]+\\}");
            var idPat = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");
            var reasonPat = Pattern.compile("\"reason\"\\s*:\\s*\"([^\"]+)\"");
            var matcher = objPattern.matcher(json);
            while (matcher.find()) {
                String obj = matcher.group();
                var im = idPat.matcher(obj);
                var rm = reasonPat.matcher(obj);
                if (im.find()) {
                    String id = im.group(1);
                    String reason = rm.find() ? rm.group(1) : "AI推荐";
                    var tech = registry.get(id);
                    if (tech.isPresent()) {
                        result.add(new TechniqueRecommender.TechniqueSuggestion(
                                id, tech.get().getName(), 0.60, "🧠 " + reason));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("解析AI重推响应失败: {}", e.getMessage());
        }
        return result;
    }
}
