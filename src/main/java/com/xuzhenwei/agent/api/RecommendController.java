package com.xuzhenwei.agent.api;

import com.xuzhenwei.agent.technique.TechniqueRecommender;
import com.xuzhenwei.agent.technique.TechniqueRegistry;
import com.xuzhenwei.agent.technique.RecommendationDecisionEngine;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 透明推荐 API — 显示推荐理由、置信度、匹配关键词
 */
@RestController
@RequestMapping("/api/recommend")
@CrossOrigin
public class RecommendController {

    private final TechniqueRecommender recommender;
    private final TechniqueRegistry registry;
    private final RecommendationDecisionEngine decisionEngine;
    private final com.xuzhenwei.agent.technique.RefreshStrategyEngine refreshEngine;

    public RecommendController(TechniqueRecommender recommender,
                                TechniqueRegistry registry,
                                RecommendationDecisionEngine decisionEngine,
                                com.xuzhenwei.agent.technique.RefreshStrategyEngine refreshEngine) {
        this.recommender = recommender;
        this.registry = registry;
        this.decisionEngine = decisionEngine;
        this.refreshEngine = refreshEngine;
    }

    /**
     * 透明推荐——返回推荐列表 + 匹配关键词 + 解释
     */
    @PostMapping("/quick")
    public Map<String, Object> quickRecommend(@RequestBody Map<String, String> req) {
        String message = req.getOrDefault("message", "");
        boolean shuffle = "true".equals(req.getOrDefault("shuffle", "false"));
        var result = recommender.recommend(message, shuffle);

        // 提取匹配的关键词
        List<String> keywords = extractKeywords(message);

        // 构建推荐卡片
        List<Map<String, Object>> cards = new ArrayList<>();
        for (var s : result.suggestions()) {
            var tech = registry.get(s.techniqueId());
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("id", s.techniqueId());
            card.put("name", s.techniqueName());
            card.put("confidence", Math.round(s.confidence() * 100));
            card.put("reason", s.reason());
            card.put("category", tech.map(t -> t.getCategory()).orElse(""));
            card.put("stepCount", tech.map(t -> t.getStepCount()).orElse(1));
            card.put("description", tech.map(t -> t.getDescription()).orElse(""));
            cards.add(card);
        }

        // 配方
        Map<String, Object> recipeInfo = null;
        if (result.recipe() != null) {
            recipeInfo = new LinkedHashMap<>();
            recipeInfo.put("name", result.recipe().name());
            recipeInfo.put("description", result.recipe().description());
            recipeInfo.put("techniqueIds", result.recipe().techniqueIds());
            List<Map<String, String>> recipeSteps = new ArrayList<>();
            for (String tid : result.recipe().techniqueIds()) {
                var t = registry.get(tid);
                t.ifPresent(tech -> recipeSteps.add(Map.of(
                        "id", tech.getId(), "name", tech.getName())));
            }
            recipeInfo.put("steps", recipeSteps);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("keywords", keywords);
        response.put("explanation", result.explanation());
        response.put("cards", cards);
        response.put("recipe", recipeInfo);
        // 复杂度元数据（v2.1 新增）
        response.put("complexity", Map.of(
                "level", result.complexity().name(),
                "label", result.complexity().getLabel(),
                "recommendCount", result.suggestions().size(),
                "maxAllowed", result.complexity().getMaxRecommend()
        ));
        // v3.0 方法1: 意图分类
        var analysis = decisionEngine.analyze(message);
        response.put("intent", Map.of(
                "type", analysis.intent().name(),
                "label", analysis.intent().getLabel()
        ));
        // v3.1: MECE覆盖计划
        if (result.mecePlan() != null && result.mecePlan().totalSlots() > 0) {
            var mece = result.mecePlan();
            List<Map<String, Object>> phases = new ArrayList<>();
            for (var phase : com.xuzhenwei.agent.technique.MeceCoverageEngine.MecePhase.values()) {
                int slots = mece.slotAllocation().getOrDefault(phase, 0);
                double demand = mece.demandStrength().getOrDefault(phase, 0.0);
                if (slots > 0) {
                    phases.add(Map.of(
                            "phase", phase.name(),
                            "label", phase.getLabel(),
                            "description", phase.getDescription(),
                            "slots", slots,
                            "demand", Math.round(demand * 100)
                    ));
                }
            }
            response.put("mece", Map.of(
                    "totalSlots", mece.totalSlots(),
                    "reasoning", mece.reasoning(),
                    "isComplete", mece.isComplete(),
                    "phases", phases
            ));
        }
        return response;
    }

    /** v3.2 多策略刷新 */
    @PostMapping("/refresh")
    public Map<String, Object> refresh(@RequestBody Map<String, Object> req) {
        String message = (String) req.getOrDefault("message", "");
        int round = req.containsKey("round") ? ((Number) req.get("round")).intValue() : 1;
        @SuppressWarnings("unchecked")
        List<String> seenIds = (List<String>) req.getOrDefault("seenIds", List.of());
        @SuppressWarnings("unchecked")
        List<String> prevCats = (List<String>) req.getOrDefault("prevCategories", List.of());

        var ctx = new com.xuzhenwei.agent.technique.RefreshStrategyEngine.RefreshContext(
                message, round,
                new java.util.LinkedHashSet<>(seenIds),
                new java.util.LinkedHashSet<>(prevCats),
                null, // 暂不传MECE计划
                decisionEngine.analyze(message)
        );

        var result = refreshEngine.refresh(ctx);

        List<Map<String, Object>> cards = new ArrayList<>();
        for (var s : result.suggestions()) {
            var tech = registry.get(s.techniqueId());
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("id", s.techniqueId());
            card.put("name", s.techniqueName());
            card.put("confidence", Math.round(s.confidence() * 100));
            card.put("reason", s.reason());
            card.put("description", tech.map(t -> t.getDescription()).orElse(""));
            cards.add(card);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("strategy", Map.of(
                "name", result.strategy().name(),
                "label", result.strategy().getLabel(),
                "description", result.strategy().getDescription()
        ));
        response.put("nextStrategy", refreshEngine.nextStrategy(round).getLabel());
        response.put("cards", cards);
        response.put("explanation", result.explanation());
        return response;
    }

    /** 深度推荐 */
    @PostMapping("/deep")
    public Map<String, String> deepRecommend(@RequestBody Map<String, String> req) {
        return Map.of("recommendation", recommender.deepRecommend(req.getOrDefault("message", "")));
    }

    /** 从用户输入提取关键词 */
    private List<String> extractKeywords(String input) {
        List<String> allKeywords = List.of(
                "赚钱", "定价", "推广", "客户", "获客", "营销", "品牌",
                "创业", "新业务", "项目", "启动",
                "产品", "策划", "方案", "提案", "汇报",
                "战略", "规划", "方向", "转型",
                "选题", "内容", "视频", "脚本", "文案",
                "风险", "漏洞", "问题", "挑刺",
                "烦恼", "困惑", "趋势", "未来",
                "验证", "执行", "落地", "行动计划",
                "财务", "预测", "用户", "满意度",
                "竞争对手", "差异化", "优势",
                "酵素菌", "西红柿", "番茄", "农产品", "农业", "培训",
                "完全没思路", "不知道怎么做", "没灵感"
        );
        List<String> found = new ArrayList<>();
        String lower = input.toLowerCase();
        for (String kw : allKeywords) {
            if (lower.contains(kw.toLowerCase())) found.add(kw);
        }
        return found.isEmpty() ? List.of("通用分析") : found;
    }
}
