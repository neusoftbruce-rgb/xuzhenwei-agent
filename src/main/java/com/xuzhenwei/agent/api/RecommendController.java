package com.xuzhenwei.agent.api;

import com.xuzhenwei.agent.technique.TechniqueRecommender;
import com.xuzhenwei.agent.technique.TechniqueRegistry;
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

    public RecommendController(TechniqueRecommender recommender, TechniqueRegistry registry) {
        this.recommender = recommender;
        this.registry = registry;
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
