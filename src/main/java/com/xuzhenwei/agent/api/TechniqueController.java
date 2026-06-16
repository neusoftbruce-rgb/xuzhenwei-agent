package com.xuzhenwei.agent.api;

import com.xuzhenwei.agent.technique.Technique;
import com.xuzhenwei.agent.technique.TechniqueRegistry;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 技法浏览 API
 *
 * <p>提供技法目录的查询接口，前端据此渲染"技法选择器"面板。
 * 按原书结构组织：4大部 × 12章 × 56技法。</p>
 */
@RestController
@RequestMapping("/api/techniques")
@CrossOrigin
public class TechniqueController {

    private final TechniqueRegistry registry;

    public TechniqueController(TechniqueRegistry registry) {
        this.registry = registry;
    }

    /** 获取全部技法列表 */
    @GetMapping
    public List<TechniqueSummary> listAll() {
        return registry.getAll().stream()
                .map(TechniqueSummary::from)
                .toList();
    }

    /** 按分类筛选技法 */
    @GetMapping("/category/{category}")
    public List<TechniqueSummary> listByCategory(@PathVariable String category) {
        return registry.getByCategory(category).stream()
                .map(TechniqueSummary::from)
                .toList();
    }

    /** 获取单个技法详情 */
    @GetMapping("/{id}")
    public TechniqueSummary getById(@PathVariable String id) {
        return registry.get(id)
                .map(TechniqueSummary::from)
                .orElseThrow(() -> new IllegalArgumentException("技法不存在: " + id));
    }

    /** 技法统计 */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return Map.of(
                "totalCount", registry.count(),
                "categories", registry.getCategories()
        );
    }

    /**
     * 技法摘要 — 前端列表展示用
     */
    public record TechniqueSummary(
            String id,
            String name,
            String category,
            String description,
            int stepCount
    ) {
        public static TechniqueSummary from(Technique t) {
            return new TechniqueSummary(
                    t.getId(), t.getName(), t.getCategory(),
                    t.getDescription(), t.getStepCount()
            );
        }
    }
}
