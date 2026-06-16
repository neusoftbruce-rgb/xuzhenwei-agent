package com.xuzhenwei.agent.api;

import com.xuzhenwei.agent.technique.TechniqueRecommender;
import com.xuzhenwei.agent.technique.TechniqueRegistry;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 技法推荐 API
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

    /** 快速推荐（关键词匹配，毫秒级） */
    @PostMapping("/quick")
    public TechniqueRecommender.RecommendationResult quickRecommend(@RequestBody Map<String, String> req) {
        return recommender.recommend(req.getOrDefault("message", ""));
    }

    /** 深度推荐（LLM分析，更精准） */
    @PostMapping("/deep")
    public Map<String, String> deepRecommend(@RequestBody Map<String, String> req) {
        return Map.of("recommendation", recommender.deepRecommend(req.getOrDefault("message", "")));
    }
}
