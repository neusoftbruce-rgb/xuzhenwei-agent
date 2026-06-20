package com.xuzhenwei.agent.technique;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.util.List;

/**
 * 智能推荐数量决策引擎 — 根据问题复杂度动态确定推荐技法数量
 *
 * <p>三层判断架构：
 * <ol>
 *   <li>复杂度分类 — 根据问题关键词判断问题复杂度等级（本地，0ms）</li>
 *   <li>置信度截断 — 在等级上限内，用分数断崖自动截断（本地，0ms）</li>
 *   <li>AI复核 — 模糊场景异步调DeepSeek确认（预留，Phase 4实施）</li>
 * </ol>
 *
 * <p>设计原则：简单问题推1-2条、单一领域推2-4条、
 * 复杂问题推3-5条、全面诊断推5-8条。替换之前硬编码的 limit(9)。</p>
 *
 * <p>复杂度判定参考了 TokenSaver.shouldDeepThink() 的关键词模式，
 * 并扩展为4级分类。</p>
 *
 * @since 2026-06-17 (v2.1)
 */
@Service
public class RecommendationDecisionEngine {

    private static final Logger log = LoggerFactory.getLogger(RecommendationDecisionEngine.class);

    /** 分数断崖阈值：相邻两条技法分数差超过此值（15个百分点）则在此截断 */
    private static final double CLIFF_THRESHOLD = 0.15;

    // ═══════════════════════════════════════════════════════════
    // 方法1: 意图分类 (Intent-First Classification)
    // ═══════════════════════════════════════════════════════════

    /**
     * 用户意图类型 —— 在关键词匹配之前先判断意图，避免"怎么赚钱"
     * 和"赚钱是什么意思"匹配到同一个技法。
     *
     * <p>5 种意图覆盖了徐振伟智能体的主要使用场景。</p>
     */
    public enum IntentType {
        /** 打招呼/闲聊 — "你好"、"谢谢"、"在吗" */
        GREETING("闲聊"),
        /** 定义/事实询问 — "酵素菌是什么"、"6W3H怎么用" */
        DEFINITION("定义询问"),
        /** 创意发散/找灵感 — "没思路"、"帮我想几个方案" */
        IDEATION("创意发散"),
        /** 打磨完善方案 — "帮我完善一下"、"挑挑毛病" */
        REFINEMENT("方案打磨"),
        /** 执行落地 — "怎么执行"、"行动计划"、"第一步做什么" */
        EXECUTION("执行落地"),
        /** 诊断分析 — "为什么会失败"、"问题根源在哪" */
        DIAGNOSIS("诊断分析");

        private final String label;

        IntentType(String label) { this.label = label; }
        public String getLabel() { return label; }
    }

    /**
     * 综合分析上下文 —— 将复杂度、意图、领域打包，
     * 下游推荐器和路由引擎据此做出更精准的决策。
     */
    public record AnalysisContext(
            ComplexityLevel complexity,
            IntentType intent,
            int inputLength,
            int questionCount,
            boolean isLongText
    ) {
        /** 是否属于简单事实型问题（不需要技法，直接回答即可） */
        public boolean isSimpleFactual() {
            return complexity == ComplexityLevel.SIMPLE_FACTUAL
                    && (intent == IntentType.GREETING || intent == IntentType.DEFINITION);
        }

        /** 是否需要深度分析 */
        public boolean needsDeepAnalysis() {
            return complexity == ComplexityLevel.COMPREHENSIVE
                    || complexity == ComplexityLevel.COMPLEX_MULTI;
        }
    }

    /**
     * 【方法1 核心】根据用户输入判断意图类型。
     *
     * <p>判定优先级（从高到低）：</p>
     * <ol>
     *   <li>打招呼/闲聊关键词 → GREETING</li>
     *   <li>定义/事实询问关键词 → DEFINITION</li>
     *   <li>诊断分析关键词 → DIAGNOSIS</li>
     *   <li>执行落地关键词 → EXECUTION</li>
     *   <li>方案打磨关键词 → REFINEMENT</li>
     *   <li>创意发散关键词 → IDEATION</li>
     *   <li>默认 → IDEATION（大多数用户都是来找灵感的）</li>
     * </ol>
     */
    public IntentType classifyIntent(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return IntentType.IDEATION;
        }

        String input = userInput.toLowerCase().trim();

        // 1. 打招呼/闲聊
        if (input.length() < 10 && matchesAny(input,
                "你好", "谢谢", "再见", "在吗", "hi", "hello", "早", "晚安")) {
            return IntentType.GREETING;
        }

        // 2. 定义/事实询问
        if (matchesAny(input,
                "是什么", "什么是", "定义", "什么意思", "含义", "概念",
                "介绍一下", "怎么用", "如何使用", "用法") && input.length() < 60) {
            return IntentType.DEFINITION;
        }

        // 3. 诊断分析（放在前面因为它有强信号词）
        if (matchesAny(input,
                "为什么", "原因", "根源", "根因", "怎么会", "为啥",
                "问题在哪", "哪里出", "怎么回事", "诊断", "深层", "本质")) {
            return IntentType.DIAGNOSIS;
        }

        // 4. 执行落地
        if (matchesAny(input,
                "执行", "怎么落地", "行动计划", "下一步", "怎么做",
                "落地", "实施", "操作", "步骤", "流程", "具体怎么",
                "wbs", "拆解", "分解", "推进")) {
            return IntentType.EXECUTION;
        }

        // 5. 方案打磨
        if (matchesAny(input,
                "完善", "打磨", "优化", "改进", "提升", "验证",
                "挑刺", "找漏洞", "查漏", "检查", "审阅", "评审",
                "可行性", "风险", "打分", "评分", "评估")) {
            return IntentType.REFINEMENT;
        }

        // 6. 创意发散（关键词最多，覆盖面广）
        if (matchesAny(input,
                "创意", "点子", "灵感", "想法", "思路", "脑暴",
                "头脑风暴", "创新", "新方法", "方案", "建议",
                "帮我想", "出出主意", "有什么办法", "怎么做才能",
                "如何", "怎么", "怎样", "有没有", "能不能",
                "设计", "策划", "规划", "定价", "推广", "营销")) {
            return IntentType.IDEATION;
        }

        // 7. 默认 → 创意发散（用户来用智能体，大概率是来找思路的）
        return IntentType.IDEATION;
    }

    /**
     * 一键全分析 —— 返回包含复杂度+意图的完整上下文
     */
    public AnalysisContext analyze(String userInput) {
        return new AnalysisContext(
                classify(userInput),
                classifyIntent(userInput),
                userInput != null ? userInput.length() : 0,
                countQuestionMarks(userInput),
                userInput != null && userInput.length() > 80
        );
    }

    /**
     * v3.4: 用预处理管道提供的意图信息，不再重新推断。
     * 预处理管道已经通过智谱精炼得到了更准确的 intent/domains/complexity。
     */
    public AnalysisContext analyzeWithIntent(String userInput,
                                              com.xuzhenwei.agent.agent.TextPreprocessor.PreprocessResult.IntentAnalysis intent) {
        IntentType intentType = switch (intent.intent()) {
            case "诊断分析" -> IntentType.DIAGNOSIS;
            case "创意发想" -> IntentType.IDEATION;
            case "方案打磨" -> IntentType.REFINEMENT;
            case "执行落地" -> IntentType.EXECUTION;
            default -> IntentType.DIAGNOSIS;
        };
        ComplexityLevel complexity = intent.complexity() >= 7 ? ComplexityLevel.COMPLEX_MULTI
            : intent.complexity() >= 5 ? ComplexityLevel.SINGLE_DOMAIN
            : ComplexityLevel.SIMPLE_FACTUAL;
        return new AnalysisContext(complexity, intentType,
            userInput != null ? userInput.length() : 0,
            intent.subQuestions().size(),
            userInput != null && userInput.length() > 200
        );
    }

    /** 统计问号数量 */
    private int countQuestionMarks(String input) {
        if (input == null) return 0;
        int count = 0;
        for (char c : input.toCharArray()) {
            if (c == '?' || c == '？') count++;
        }
        return count;
    }

    // ═══════════════════════════════════════════════════════════
    // 原有复杂度分类（保持不变）
    // ═══════════════════════════════════════════════════════════

    /**
     * 问题复杂度等级
     */
    public enum ComplexityLevel {
        /** 简单事实/定义问题 — 推荐1-2条 */
        SIMPLE_FACTUAL(1, 2, "简单问题"),
        /** 单一领域问题 — 推荐2-4条 */
        SINGLE_DOMAIN(2, 4, "单一领域"),
        /** 复杂多领域问题 — 推荐3-5条 */
        COMPLEX_MULTI(3, 5, "复杂多领域"),
        /** 全面诊断/战略问题 — 推荐5-8条 */
        COMPREHENSIVE(5, 8, "全面诊断");

        private final int minRecommend;
        private final int maxRecommend;
        private final String label;

        ComplexityLevel(int min, int max, String label) {
            this.minRecommend = min;
            this.maxRecommend = max;
            this.label = label;
        }

        public int getMinRecommend() { return minRecommend; }
        public int getMaxRecommend() { return maxRecommend; }
        public String getLabel() { return label; }
    }

    /**
     * 【第一层】问题复杂度分类
     *
     * <p>判定优先级（从高到低）：</p>
     * <ol>
     *   <li>全面诊断/战略关键词 → COMPREHENSIVE</li>
     *   <li>长文本(80字+) + 分析关键词 → COMPREHENSIVE</li>
     *   <li>事实/定义询问关键词 + 短文本 → SIMPLE_FACTUAL</li>
     *   <li>多领域交叉(≥2领域 或 >50字) → COMPLEX_MULTI</li>
     *   <li>默认 → SINGLE_DOMAIN</li>
     * </ol>
     *
     * @param userInput 用户输入文本
     * @return 复杂度等级
     */
    public ComplexityLevel classify(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return ComplexityLevel.SINGLE_DOMAIN;
        }

        String input = userInput.toLowerCase().trim();
        int length = userInput.length();

        // 1. 全面诊断/战略型关键词 → COMPREHENSIVE
        if (matchesAny(input,
                "全面", "综合诊断", "整体", "战略", "改革", "转型", "重构",
                "全盘", "通盘", "彻底", "系统性")) {
            log.debug("分类=COMPREHENSIVE (战略关键词)");
            return ComplexityLevel.COMPREHENSIVE;
        }
        // 长文本(80字+) + 分析/诊断/规划关键词 → COMPREHENSIVE
        if (length > 80 && matchesAny(input,
                "分析", "诊断", "评估", "规划", "设计", "方案")) {
            log.debug("分类=COMPREHENSIVE (长文本{}字+分析)", length);
            return ComplexityLevel.COMPREHENSIVE;
        }

        // 2. 简单事实/定义询问 → SIMPLE_FACTUAL
        if (matchesAny(input,
                "是什么", "什么是", "定义", "介绍一下", "介绍", "怎么用",
                "什么意思", "含义", "概念") && length < 40) {
            log.debug("分类=SIMPLE_FACTUAL (定义询问)");
            return ComplexityLevel.SIMPLE_FACTUAL;
        }
        // 极短交互
        if (matchesAny(input, "你好", "谢谢", "再见") && length < 10) {
            log.debug("分类=SIMPLE_FACTUAL (简单交互)");
            return ComplexityLevel.SIMPLE_FACTUAL;
        }

        // 3. 多领域交叉检测
        int domainCount = countDomainMatches(input);
        if (domainCount >= 2 || length > 50) {
            log.debug("分类=COMPLEX_MULTI (领域数={}, 字数={})", domainCount, length);
            return ComplexityLevel.COMPLEX_MULTI;
        }

        // 4. 默认
        log.debug("分类=SINGLE_DOMAIN (默认)");
        return ComplexityLevel.SINGLE_DOMAIN;
    }

    /**
     * 【第二层】置信度截断
     *
     * <p>在复杂度等级上限内，根据分数断崖自动确定实际推荐条数。</p>
     *
     * <p>算法：从 minRecommend 条开始检查，如果第 i 条和第 i+1 条
     * 的分数差超过 CLIFF_THRESHOLD，则在第 i 条截断。否则继续累加直到
     * maxRecommend 上限。</p>
     *
     * @param sorted 按分数降序排列的匹配结果（需实现 ScoredItem 接口）
     * @param level  复杂度等级
     * @return 推荐条数
     */
    public int determineCutoff(List<? extends ScoredItem> sorted, ComplexityLevel level) {
        if (sorted.isEmpty()) return 0;

        int max = level.getMaxRecommend();
        int min = Math.min(level.getMinRecommend(), sorted.size());

        // 结果数本身就很少，全返回
        if (sorted.size() <= min) return sorted.size();

        // 从 min 条开始，逐个检查分数断崖
        int cutoff = min;
        for (int i = min - 1; i < sorted.size() - 1 && i < max - 1; i++) {
            double gap = sorted.get(i).score() - sorted.get(i + 1).score();
            if (gap > CLIFF_THRESHOLD) {
                cutoff = i + 1;
                log.debug("分数断崖截断 @ {}", cutoff);
                break;
            }
            cutoff = i + 2;
        }

        int result = Math.min(cutoff, max);
        log.debug("推荐数量={}/{} (等级={} 上限={})", result, sorted.size(), level, max);
        return result;
    }

    /**
     * 判断是否需要AI复核（预留，Phase 4 实施）
     *
     * <p>触发条件（规划中）：</p>
     * <ul>
     *   <li>COMPLEX_MULTI 级别但匹配置信度平均值 < 0.5</li>
     *   <li>高分匹配数量远超 maxRecommend（关键词太宽泛）</li>
     * </ul>
     */
    public boolean needsAiReview(ComplexityLevel level, List<? extends ScoredItem> results) {
        return false; // Phase 4 实施
    }

    // ---- 私有辅助方法 ----

    /** 检查输入是否包含任意一个关键词 */
    private boolean matchesAny(String input, String... keywords) {
        for (String kw : keywords) {
            if (input.contains(kw)) return true;
        }
        return false;
    }

    /** 统计输入涉及几个业务领域 */
    private int countDomainMatches(String input) {
        int count = 0;
        if (matchesAny(input, "酵素菌", "堆肥", "微生物", "土壤", "肥料", "病害", "虫害", "农药", "种植"))
            count++;
        if (matchesAny(input, "赚钱", "定价", "财务", "成本", "收入", "利润", "商业模式", "获客", "营销", "推广", "销售", "品牌", "市场"))
            count++;
        if (matchesAny(input, "团队", "管理", "培训", "招聘", "考核", "绩效", "组织", "人才"))
            count++;
        if (matchesAny(input, "产品", "开发", "技术", "养殖", "加工", "冷链", "包装"))
            count++;
        if (matchesAny(input, "视频", "短视频", "抖音", "文案", "选题", "自媒体", "内容", "直播"))
            count++;
        return count;
    }

    /**
     * 分数项接口 — 供 determineCutoff 对任意匹配结果做分数分析
     */
    public interface ScoredItem {
        double score();
    }
}
