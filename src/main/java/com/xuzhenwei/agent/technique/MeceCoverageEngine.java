package com.xuzhenwei.agent.technique;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * MECE 覆盖引擎 — v3.1 核心
 *
 * <p>基于 McKinsey MECE 原则（Mutually Exclusive, Collectively Exhaustive）
 * 实现技法组合的自动选择：确保选出的技法不重叠、不遗漏，覆盖完整的"发散→收敛→验证→执行"闭环。</p>
 *
 * <p>设计参考：
 * <ul>
 *   <li>MECE 原则: 互斥(Mutually Exclusive) + 穷尽(Collectively Exhaustive)</li>
 *   <li>4阶段闭环: 发散(Ideation) → 收敛(Refinement) → 验证(Verification) → 执行(Execution)</li>
 *   <li>需求强度算法: 基于用户意图、复杂度、输入特征动态分配技法槽位</li>
 * </ul>
 *
 * @since 2026-06-19
 */
@Service
public class MeceCoverageEngine {

    private static final Logger log = LoggerFactory.getLogger(MeceCoverageEngine.class);

    /** 最大技法槽位数 */
    public static final int MAX_SLOTS = 9;
    /** 最小技法数 */
    public static final int MIN_SLOTS = 3;
    /** 每个阶段至少保留的槽位数 */
    private static final int MIN_PER_PHASE = 1;

    // ═══════════════════════════════════════════════════════════
    // 数据模型
    // ═══════════════════════════════════════════════════════════

    /** MECE 四阶段 */
    public enum MecePhase {
        IDEATION("🎨 发散", "打开思路，产生新创意和可能性"),
        REFINEMENT("🔧 收敛", "结构化方案，打磨完善细节"),
        VERIFICATION("🔍 验证", "挑刺检查，评估风险和可行性"),
        EXECUTION("🚀 执行", "落地行动，转化为可执行计划");

        private final String label;
        private final String description;
        MecePhase(String label, String desc) { this.label = label; this.description = desc; }
        public String getLabel() { return label; }
        public String getDescription() { return description; }
    }

    /** MECE 覆盖计划 */
    public record MecePlan(
            Map<MecePhase, Double> demandStrength,    // 每个阶段的需求强度 (0-1)
            Map<MecePhase, Integer> slotAllocation,    // 每个阶段分配的槽位数
            Map<MecePhase, List<String>> assignedTechIds, // 每个阶段分配的技法ID
            int totalSlots,                             // 总槽位数
            String reasoning,                           // 判断理由
            boolean isComplete                          // 是否四阶段完整闭环
    ) {
        /** 哪些阶段有需求（强度 > 0.3） */
        public List<MecePhase> getActivePhases() {
            return demandStrength.entrySet().stream()
                    .filter(e -> e.getValue() > 0.3)
                    .map(Map.Entry::getKey)
                    .toList();
        }

        /** 空计划（用于简单问题） */
        public static MecePlan empty(String reason) {
            return new MecePlan(Map.of(), Map.of(), Map.of(), 0, reason, false);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 核心算法: 需求强度计算 + 槽位分配
    // ═══════════════════════════════════════════════════════════

    /**
     * 根据用户输入和意图分析，生成 MECE 覆盖计划。
     *
     * @param context  意图+复杂度分析结果
     * @param userInput 用户原始输入
     * @return MECE 计划
     */
    public MecePlan plan(RecommendationDecisionEngine.AnalysisContext context, String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return MecePlan.empty("空输入");
        }

        String lower = userInput.toLowerCase();
        var complexity = context.complexity();
        var intent = context.intent();

        // 简单问题：不需要 MECE，直接1-2个技法
        if (intent == RecommendationDecisionEngine.IntentType.GREETING
                || intent == RecommendationDecisionEngine.IntentType.DEFINITION
                || complexity == RecommendationDecisionEngine.ComplexityLevel.SIMPLE_FACTUAL) {
            return MecePlan.empty("简单问题(" + intent.getLabel() + "/" + complexity.getLabel() + ")，无需MECE覆盖");
        }

        // === 步骤1: 计算每个阶段的需求强度 ===
        Map<MecePhase, Double> demand = new LinkedHashMap<>();
        demand.put(MecePhase.IDEATION, calcIdeationDemand(intent, complexity, lower));
        demand.put(MecePhase.REFINEMENT, calcRefinementDemand(intent, complexity, lower, context));
        demand.put(MecePhase.VERIFICATION, calcVerificationDemand(intent, complexity, lower, context));
        demand.put(MecePhase.EXECUTION, calcExecutionDemand(intent, complexity, lower));

        // === 步骤2: 确定总槽位数 ===
        int totalSlots = determineTotalSlots(complexity, context.inputLength(), context.questionCount());

        // === 步骤3: 按需求强度分配槽位 ===
        Map<MecePhase, Integer> allocation = allocateSlots(demand, totalSlots);

        // === 步骤4: 检查闭环完整性 ===
        boolean complete = allocation.values().stream().filter(v -> v > 0).count() >= 3;

        // === 步骤5: 生成判断理由 ===
        String reasoning = buildReasoning(intent, complexity, demand, allocation, complete);

        log.debug("MECE计划: totalSlots={}, allocation={}, complete={}", totalSlots, allocation, complete);
        return new MecePlan(demand, allocation, new LinkedHashMap<>(), totalSlots, reasoning, complete);
    }

    /**
     * 将 MECE 计划与技法匹配结果结合，填充每个阶段的技法 ID。
     */
    public MecePlan fillTechniques(MecePlan plan, Map<MecePhase, List<TechniqueRecommender.TechniqueSuggestion>> candidates) {
        Map<MecePhase, List<String>> assigned = new LinkedHashMap<>();
        Set<String> used = new LinkedHashSet<>(); // 确保互斥(Mutually Exclusive)

        for (var phase : MecePhase.values()) {
            int slots = plan.slotAllocation().getOrDefault(phase, 0);
            var suggestions = candidates.getOrDefault(phase, List.of());
            List<String> techIds = new ArrayList<>();

            for (var s : suggestions) {
                if (techIds.size() >= slots) break;
                if (!used.contains(s.techniqueId())) {
                    techIds.add(s.techniqueId());
                    used.add(s.techniqueId());
                }
            }
            assigned.put(phase, techIds);
        }

        return new MecePlan(plan.demandStrength(), plan.slotAllocation(), assigned,
                plan.totalSlots(), plan.reasoning(), plan.isComplete());
    }

    // ═══════════════════════════════════════════════════════════
    // 需求强度计算 (私有)
    // ═══════════════════════════════════════════════════════════

    /** 发散需求：意图即创意 + 无思路信号 */
    private double calcIdeationDemand(RecommendationDecisionEngine.IntentType intent,
                                       RecommendationDecisionEngine.ComplexityLevel complexity,
                                       String lower) {
        double base = 0.5;
        if (intent == RecommendationDecisionEngine.IntentType.IDEATION) base = 0.9;
        if (intent == RecommendationDecisionEngine.IntentType.DIAGNOSIS) base = 0.7;
        if (intent == RecommendationDecisionEngine.IntentType.DEFINITION) base = 0.3;
        // "没思路"/"不知道"等无思路信号加强
        if (containsAny(lower, "没思路", "不知道", "怎么办", "有什么办法", "帮我想", "没灵感", "脑暴")) base = Math.min(1.0, base + 0.15);
        // 战略/大方向问题也需要发散
        if (containsAny(lower, "战略", "方向", "未来", "规划", "创新", "突破", "改革", "转型")) base = Math.min(1.0, base + 0.1);
        return base;
    }

    /** 收敛需求：长文 + 多问题 + 打磨意图 */
    private double calcRefinementDemand(RecommendationDecisionEngine.IntentType intent,
                                         RecommendationDecisionEngine.ComplexityLevel complexity,
                                         String lower,
                                         RecommendationDecisionEngine.AnalysisContext ctx) {
        double base = 0.5;
        if (intent == RecommendationDecisionEngine.IntentType.REFINEMENT) base = 0.9;
        if (intent == RecommendationDecisionEngine.IntentType.EXECUTION) base = 0.7;
        // 长文 → 需要结构化
        if (ctx.isLongText()) base = Math.min(1.0, base + 0.2);
        if (ctx.questionCount() >= 2) base = Math.min(1.0, base + 0.1);
        // 方案/策划/定价关键词
        if (containsAny(lower, "方案", "策划", "定价", "计划", "设计", "完善", "优化")) base = Math.min(1.0, base + 0.1);
        // 商业模式/财务/预算
        if (containsAny(lower, "商业模式", "财务", "预算", "成本", "收入", "利润")) base = Math.min(1.0, base + 0.15);
        return base;
    }

    /** 验证需求：高风险 + 复杂 + 打磨意图 */
    private double calcVerificationDemand(RecommendationDecisionEngine.IntentType intent,
                                           RecommendationDecisionEngine.ComplexityLevel complexity,
                                           String lower,
                                           RecommendationDecisionEngine.AnalysisContext ctx) {
        double base = 0.4;
        if (complexity == RecommendationDecisionEngine.ComplexityLevel.COMPREHENSIVE) base = 0.9;
        if (complexity == RecommendationDecisionEngine.ComplexityLevel.COMPLEX_MULTI) base = 0.7;
        if (intent == RecommendationDecisionEngine.IntentType.REFINEMENT) base = Math.min(1.0, base + 0.15);
        // 高风险信号
        if (containsAny(lower, "风险", "漏洞", "挑刺", "验证", "检查", "评审", "可行", "可行吗")) base = Math.min(1.0, base + 0.2);
        // 投资/创业 → 更需要验证
        if (containsAny(lower, "投资", "创业", "启动", "融资")) base = Math.min(1.0, base + 0.15);
        return base;
    }

    /** 执行需求：执行意图 + 行动关键词 */
    private double calcExecutionDemand(RecommendationDecisionEngine.IntentType intent,
                                        RecommendationDecisionEngine.ComplexityLevel complexity,
                                        String lower) {
        double base = 0.3;
        if (intent == RecommendationDecisionEngine.IntentType.EXECUTION) base = 0.9;
        // 行动关键词
        if (containsAny(lower, "执行", "行动计划", "落地", "怎么做", "下一步", "实施", "操作", "步骤", "流程",
                "推广", "获客", "营销", "视频", "短视频")) base = Math.min(1.0, base + 0.3);
        return base;
    }

    // ═══════════════════════════════════════════════════════════
    // 槽位分配
    // ═══════════════════════════════════════════════════════════

    /** 根据复杂度确定总槽位数 */
    private int determineTotalSlots(RecommendationDecisionEngine.ComplexityLevel complexity,
                                     int inputLength, int questionCount) {
        return switch (complexity) {
            case COMPREHENSIVE -> Math.min(MAX_SLOTS, 7 + Math.min(questionCount, 2));
            case COMPLEX_MULTI -> inputLength > 200 ? 6 : 5;
            case SINGLE_DOMAIN -> inputLength > 100 ? 4 : MIN_SLOTS;
            default -> MIN_SLOTS;
        };
    }

    /** 按需求强度比例分配槽位，保证每个高需求阶段至少 MIN_PER_PHASE 个 */
    private Map<MecePhase, Integer> allocateSlots(Map<MecePhase, Double> demand, int totalSlots) {
        Map<MecePhase, Integer> alloc = new LinkedHashMap<>();
        double total = demand.values().stream().mapToDouble(Double::doubleValue).sum();

        if (total == 0) {
            for (var p : MecePhase.values()) alloc.put(p, 0);
            return alloc;
        }

        // 第一轮：按比例分配
        int assigned = 0;
        for (var entry : demand.entrySet()) {
            int slots = (int) Math.round(totalSlots * entry.getValue() / total);
            // 有需求 (>0.35) 的阶段至少给 MIN_PER_PHASE 个
            if (entry.getValue() > 0.35 && slots < MIN_PER_PHASE) slots = MIN_PER_PHASE;
            alloc.put(entry.getKey(), slots);
            assigned += slots;
        }

        // 第二轮：调整溢出/不足
        while (assigned > totalSlots) {
            // 从槽位最多的阶段减1
            var maxEntry = alloc.entrySet().stream()
                    .filter(e -> e.getValue() > MIN_PER_PHASE)
                    .max(Map.Entry.comparingByValue());
            if (maxEntry.isPresent()) {
                alloc.put(maxEntry.get().getKey(), maxEntry.get().getValue() - 1);
                assigned--;
            } else break;
        }
        while (assigned < totalSlots) {
            // 给需求最高的阶段加1
            var topPhase = demand.entrySet().stream()
                    .max(Map.Entry.comparingByValue());
            if (topPhase.isPresent()) {
                var phase = topPhase.get().getKey();
                alloc.put(phase, alloc.getOrDefault(phase, 0) + 1);
                assigned++;
            } else break;
        }

        return alloc;
    }

    // ═══════════════════════════════════════════════════════════
    // 辅助方法
    // ═══════════════════════════════════════════════════════════

    private String buildReasoning(RecommendationDecisionEngine.IntentType intent,
                                   RecommendationDecisionEngine.ComplexityLevel complexity,
                                   Map<MecePhase, Double> demand,
                                   Map<MecePhase, Integer> allocation,
                                   boolean complete) {
        var sb = new StringBuilder();
        sb.append("意图=").append(intent.getLabel())
          .append(" · 复杂度=").append(complexity.getLabel())
          .append(" · 总槽位=").append(allocation.values().stream().mapToInt(Integer::intValue).sum());

        // 列出各阶段分配
        List<String> phaseInfo = new ArrayList<>();
        for (var phase : MecePhase.values()) {
            int slots = allocation.getOrDefault(phase, 0);
            if (slots > 0) {
                phaseInfo.add(phase.getLabel() + "×" + slots);
            }
        }
        sb.append(" · 分配: ").append(String.join("→", phaseInfo));
        sb.append(" · ").append(complete ? "✅四阶段闭环" : "⚠️部分覆盖");
        return sb.toString();
    }

    private boolean containsAny(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }

    /**
     * 获取某个 MECE 阶段对应的技法分类关键词，用于在 Registry 中筛选技法。
     */
    public static List<String> getPhaseCategories(MecePhase phase) {
        return switch (phase) {
            case IDEATION -> List.of("快速产生灵感");
            case REFINEMENT -> List.of("打磨完善创意");
            case VERIFICATION -> List.of("打磨完善创意", "找到思考切入点");
            case EXECUTION -> List.of("落地执行创意");
        };
    }
}
