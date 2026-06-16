package com.xuzhenwei.agent.api;

import com.xuzhenwei.agent.agent.DomainAdvisor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 领域知识 API — 徐振伟的农业业务与56技法的横向联系
 */
@RestController
@RequestMapping("/api/domain")
@CrossOrigin
public class DomainController {

    private final DomainAdvisor advisor;

    public DomainController(DomainAdvisor advisor) {
        this.advisor = advisor;
    }

    /** 获取所有业务板块及其推荐技法 */
    @GetMapping("/businesses")
    public List<BusinessVO> listBusinesses() {
        return advisor.getAllBusinesses().stream()
                .map(b -> new BusinessVO(
                        b.id, b.name, b.targetCustomers,
                        b.painPoints, b.suggestedTechniques))
                .toList();
    }

    /** 根据用户输入匹配业务板块 */
    @PostMapping("/match")
    public Map<String, Object> match(@RequestBody Map<String, String> request) {
        String input = request.getOrDefault("message", "");
        var matched = advisor.matchBusiness(input);

        return Map.of(
                "input", input,
                "matched", matched.map(b -> b.name).orElse("未匹配到具体业务，使用通用模式"),
                "suggestions", matched.map(b -> b.suggestedTechniques).orElse(List.of())
        );
    }

    /** 获取领域知识系统提示词 */
    @GetMapping("/context")
    public Map<String, String> getContext() {
        return Map.of("context", advisor.buildDomainContext());
    }

    public record BusinessVO(
            String id, String name, String targetCustomers,
            List<String> painPoints,
            List<DomainAdvisor.TechniqueRef> suggestedTechniques) {}
}
