package com.xuzhenwei.agent.agent;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.util.*;

/**
 * 领域知识顾问 — 将徐振伟的农业培训业务与56技法建立横向联系
 *
 * <p>读取 domain-knowledge.yml，提供：
 * <ul>
 *   <li>按业务板块推荐技法</li>
 *   <li>自动识别用户输入对应的业务领域</li>
 *   <li>给 Agent 注入领域知识上下文</li>
 * </ul>
 */
@Component
public class DomainAdvisor {

    private static final Logger log = LoggerFactory.getLogger(DomainAdvisor.class);

    @Value("classpath:domain-knowledge.yml")
    private Resource domainYaml;

    private DomainData domain;

    @PostConstruct
    public void init() {
        try {
            Yaml yaml = new Yaml();
            @SuppressWarnings("unchecked")
            Map<String, Object> root = yaml.load(domainYaml.getInputStream());
            @SuppressWarnings("unchecked")
            Map<String, Object> d = (Map<String, Object>) root.get("domain");
            domain = parseDomain(d);
            log.info("领域知识加载完成：{} 个业务板块", domain.businesses.size());
        } catch (Exception e) {
            log.error("加载 domain-knowledge.yml 失败: {}", e.getMessage(), e);
            domain = new DomainData();
        }
    }

    @SuppressWarnings("unchecked")
    private DomainData parseDomain(Map<String, Object> d) {
        DomainData dd = new DomainData();
        dd.name = (String) d.get("name");
        dd.description = (String) d.get("description");

        List<Map<String, Object>> bizList = (List<Map<String, Object>>) d.get("businesses");
        if (bizList != null) {
            for (Map<String, Object> b : bizList) {
                Business biz = new Business();
                biz.id = (String) b.get("id");
                biz.name = (String) b.get("name");
                biz.targetCustomers = (String) b.get("target_customers");
                biz.revenueModel = (String) b.get("revenue_model");
                biz.painPoints = (List<String>) b.get("pain_points");

                List<Map<String, Object>> techs = (List<Map<String, Object>>) b.get("suggested_techniques");
                if (techs != null) {
                    for (Map<String, Object> t : techs) {
                        biz.suggestedTechniques.add(new TechniqueRef(
                                (String) t.get("id"), (String) t.get("reason")));
                    }
                }
                dd.businesses.add(biz);
            }
        }

        dd.expertise = (List<String>) d.get("expertise");
        if (dd.expertise == null) dd.expertise = List.of();
        return dd;
    }

    /** 按关键词匹配业务板块 */
    public Optional<Business> matchBusiness(String userInput) {
        String lower = userInput.toLowerCase();
        return domain.businesses.stream()
                .filter(b -> b.keywords().stream().anyMatch(lower::contains))
                .findFirst();
    }

    /** 获取某个业务的推荐技法列表 */
    public List<TechniqueRef> getSuggestions(String businessId) {
        return domain.businesses.stream()
                .filter(b -> b.id.equals(businessId))
                .findFirst()
                .map(b -> b.suggestedTechniques)
                .orElse(List.of());
    }

    /** 获取所有业务板块 */
    public List<Business> getAllBusinesses() {
        return domain.businesses;
    }

    /** 构建领域知识系统提示词 */
    public String buildDomainContext() {
        StringBuilder sb = new StringBuilder();
        sb.append("## 用户背景（徐振伟）\n");
        sb.append(domain.name).append("：").append(domain.description).append("\n\n");
        sb.append("核心业务：\n");
        for (Business b : domain.businesses) {
            sb.append("- **").append(b.name).append("**：").append(b.targetCustomers)
              .append("，盈利模式：").append(b.revenueModel).append("\n");
        }
        sb.append("\n技术专长：").append(String.join("、", domain.expertise)).append("\n");
        return sb.toString();
    }

    // ---- 数据类 ----

    public static class DomainData {
        public String name = "";
        public String description = "";
        public List<Business> businesses = new ArrayList<>();
        public List<String> expertise = List.of();
    }

    public static class Business {
        public String id;
        public String name;
        public String targetCustomers;
        public String revenueModel;
        public List<String> painPoints = List.of();
        public List<TechniqueRef> suggestedTechniques = new ArrayList<>();

        public List<String> keywords() {
            // FIX-06: 扩展为 id + name + painPoints前三项，提高匹配覆盖
            List<String> base = new ArrayList<>();
            base.add(id);
            base.add(name);
            if (painPoints != null) {
                painPoints.stream().limit(3).forEach(base::add);
            }
            return base;
        }
    }

    public record TechniqueRef(String id, String reason) {}
}
