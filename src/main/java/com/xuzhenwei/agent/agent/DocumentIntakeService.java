package com.xuzhenwei.agent.agent;

import com.xuzhenwei.agent.technique.TechniqueRecommender;
import com.xuzhenwei.agent.technique.TechniqueRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 文档智能预分析服务 — v2.5
 *
 * <p>核心思路：在正式分析前，先用本地+轻量模型判断"需要几个技法"。
 * Token消耗：本地0 + 轻量模型~200tokens = 几乎免费。
 *
 * <p>两阶段架构：
 * <ol>
 *   <li>本地分析(0 Token): 字数/关键词/问题类型/复杂度评分</li>
 *   <li>轻量模型确认(~200 Token): 发送摘要→确认复杂度+技法数</li>
 * </ol>
 *
 * <p>路由规则：
 * <ul>
 *   <li>复杂度 1-2 → 1-2个技法(直接执行)</li>
 *   <li>复杂度 3-4 → 3-5个技法(批量管道)</li>
 *   <li>复杂度 5+ → 6-9个技法(批量+汇总)</li>
 * </ul>
 */
@Service
public class DocumentIntakeService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIntakeService.class);

    private final ChatClient chatClient;
    private final TechniqueRegistry registry;
    private final Recommender recommender;

    public DocumentIntakeService(ChatClient chatClient,
                                 TechniqueRegistry registry) {
        this.chatClient = chatClient;
        this.registry = registry;
        this.recommender = new Recommender();
    }

    /**
     * 预分析结果
     */
    public record IntakeResult(
            int complexity,              // 1-10 复杂度
            int recommendedTechCount,    // 建议技法数 1-9
            String complexityLabel,      // "简单问题"/"中等复杂"/"高度复杂"
            List<String> suggestedTechIds, // 建议的技法ID列表
            List<String> domains,         // 涉及的领域
            String reasoning,             // 判断依据
            int estimatedTokens           // 预估Token消耗
    ) {}

    /**
     * 完整预分析：本地+模型
     */
    public IntakeResult analyze(String content) {
        // === Phase 1: 本地分析 (0 Token) ===
        var localResult = localAnalysis(content);

        // 简单内容直接返回本地结果，不浪费模型调用
        if (localResult.complexity <= 2 && content.length() < 200) {
            log.info("简单内容，跳过模型确认: complexity={}", localResult.complexity);
            return localResult;
        }

        // === Phase 2: 轻量模型确认 (~200 Token) ===
        try {
            var modelResult = lightweightModelCheck(content, localResult);
            // 取两者中较高的复杂度（保守估计）
            int finalComplexity = Math.max(localResult.complexity, modelResult.complexity);
            return new IntakeResult(
                    finalComplexity,
                    complexityToTechCount(finalComplexity),
                    complexityLabel(finalComplexity),
                    modelResult.suggestedTechIds.isEmpty() ?
                            localResult.suggestedTechIds : modelResult.suggestedTechIds,
                    merge(localResult.domains, modelResult.domains),
                    modelResult.reasoning,
                    estimateTokens(finalComplexity, content.length())
            );
        } catch (Exception e) {
            log.warn("轻量模型确认失败，使用本地分析结果: {}", e.getMessage());
            return localResult;
        }
    }

    /**
     * 纯本地分析（不调模型，0 Token）
     */
    public IntakeResult localOnly(String content) {
        return localAnalysis(content);
    }

    // ====== 私有方法 ======

    /** Phase 1: 本地分析 */
    private IntakeResult localAnalysis(String content) {
        if (content == null || content.isBlank()) {
            return new IntakeResult(1, 1, "空内容", List.of("003"), List.of(), "内容为空", 0);
        }

        String lower = content.toLowerCase();
        int chars = content.length();
        int lines = content.split("\n").length;
        int questionCount = countMatches(content, "？?");
        boolean hasFileMarkers = content.contains("===") || content.contains("---");

        // 1. 复杂度评分
        int score = 0;

        // 字数因子：长文=复杂
        if (chars > 5000) score += 4;
        else if (chars > 2000) score += 3;
        else if (chars > 500) score += 2;
        else if (chars > 100) score += 1;

        // 问题因子：多问题=复杂
        if (questionCount >= 5) score += 3;
        else if (questionCount >= 3) score += 2;
        else if (questionCount >= 1) score += 1;

        // 关键词因子
        int keywordScore = countDomainKeywords(lower);
        score += Math.min(keywordScore, 3);

        // 结构因子
        if (hasFileMarkers) score += 1;
        if (lines > 50) score += 1;

        int complexity = Math.max(1, Math.min(10, score));

        // 2. 领域识别
        List<String> domains = detectDomains(lower);

        // 3. 技法建议
        List<String> suggestedTechs = suggestTechniques(complexity, domains, lower);

        return new IntakeResult(
                complexity,
                complexityToTechCount(complexity),
                complexityLabel(complexity),
                suggestedTechs,
                domains,
                buildReasoning(chars, questionCount, keywordScore, complexity, suggestedTechs.size()),
                estimateTokens(complexity, chars)
        );
    }

    /** Phase 2: 轻量模型确认 */
    private IntakeResult lightweightModelCheck(String content, IntakeResult local) {
        // 只发送前500字摘要，节省Token
        String summary = content.substring(0, Math.min(500, content.length()));
        if (content.length() > 500) summary += "\n...(共" + content.length() + "字)";

        String prompt = """
                分析以下内容，仅返回JSON：
                {"complexity":1-10, "techniqueCount":1-9, "domains":["领域1"], "reason":"一句话"}

                内容摘要：
                %s
                """.formatted(summary);

        String response = chatClient.prompt()
                .user(prompt)
                .options(OpenAiChatOptions.builder()
                        .model("glm-5.2")
                        .maxTokens(150)   // 极小输出，极省Token
                        .temperature(0.1) // 最低温度
                        .build())
                .call()
                .content();

        // 解析JSON响应
        int complexity = extractInt(response, "complexity", local.complexity);
        int techCount = extractInt(response, "techniqueCount", local.recommendedTechCount);
        List<String> domains = extractList(response, "domains");
        String reason = extractString(response, "reason", "模型确认");

        return new IntakeResult(
                complexity, techCount, complexityLabel(complexity),
                List.of(), domains, reason, 0
        );
    }

    // ====== 辅助方法 ======

    private int complexityToTechCount(int complexity) {
        if (complexity <= 1) return 1;
        if (complexity <= 2) return 2;
        if (complexity <= 4) return 3;
        if (complexity <= 6) return 5;
        if (complexity <= 8) return 7;
        return 9;
    }

    private String complexityLabel(int c) {
        if (c <= 2) return "简单问题";
        if (c <= 4) return "中等复杂";
        if (c <= 7) return "高度复杂";
        return "全面战略";
    }

    private int estimateTokens(int complexity, int chars) {
        // 粗略估算：技法数 × 2000 tokens/技法
        return complexityToTechCount(complexity) * 2000;
    }

    private int countMatches(String text, String chars) {
        int count = 0;
        for (char c : text.toCharArray()) {
            if (chars.indexOf(c) >= 0) count++;
        }
        return count;
    }

    private int countDomainKeywords(String lower) {
        int score = 0;
        // 农业
        if (containsAny(lower, "农业", "种植", "大棚", "土壤", "肥料", "病害", "虫害", "酵素菌", "微生物", "农场", "西红柿", "番茄")) score++;
        // 商业
        if (containsAny(lower, "赚钱", "定价", "成本", "利润", "营销", "获客", "商业模式", "收入", "品牌", "市场")) score++;
        // 战略
        if (containsAny(lower, "战略", "规划", "转型", "改革", "方向", "未来", "5年", "三年", "愿景")) score++;
        // 团队
        if (containsAny(lower, "团队", "管理", "培训", "招聘", "组织", "人才", "考核")) score++;
        // 技术
        if (containsAny(lower, "技术", "开发", "系统", "AI", "人工智能", "自动化", "数字")) score++;
        return score;
    }

    private boolean containsAny(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }

    private List<String> detectDomains(String lower) {
        List<String> domains = new ArrayList<>();
        if (containsAny(lower, "农业", "种植", "大棚", "土壤", "肥料", "病害", "农场", "酵素菌", "微生物")) domains.add("农业技术");
        if (containsAny(lower, "赚钱", "定价", "成本", "利润", "营销", "获客", "商业模式", "收入", "品牌")) domains.add("商业经营");
        if (containsAny(lower, "战略", "规划", "转型", "方向", "未来")) domains.add("战略规划");
        if (containsAny(lower, "团队", "管理", "培训", "招聘", "人才")) domains.add("组织管理");
        if (containsAny(lower, "技术", "AI", "人工智能", "数字化", "系统")) domains.add("技术应用");
        if (domains.isEmpty()) domains.add("通用分析");
        return domains;
    }

    private List<String> suggestTechniques(int complexity, List<String> domains, String lower) {
        List<String> techs = new ArrayList<>();
        int count = complexityToTechCount(complexity);

        // 按领域→技法映射
        Map<String, List<String>> domainTechMap = Map.of(
                "农业技术", List.of("005", "040", "022", "038", "001"),
                "商业经营", List.of("034", "026", "035", "050", "048"),
                "战略规划", List.of("005", "015", "030", "038", "025"),
                "组织管理", List.of("005", "044", "038", "047"),
                "技术应用", List.of("054", "013", "025", "037"),
                "通用分析", List.of("003", "005", "025", "040", "044", "030")
        );

        Set<String> added = new LinkedHashSet<>();
        for (String domain : domains) {
            var dTechs = domainTechMap.getOrDefault(domain, domainTechMap.get("通用分析"));
            for (String t : dTechs) {
                if (added.size() >= count) break;
                if (registry.get(t).isPresent()) added.add(t);
            }
            if (added.size() >= count) break;
        }

        // 如果还不够，补充通用技法
        List<String> fallback = List.of("003", "005", "025", "038", "044", "030", "040", "034", "015");
        for (String t : fallback) {
            if (added.size() >= count) break;
            added.add(t);
        }

        return new ArrayList<>(added);
    }

    private String buildReasoning(int chars, int questions, int keywords, int complexity, int techCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("本地分析: ");
        sb.append(chars).append("字, ");
        if (questions > 0) sb.append(questions).append("个问题, ");
        sb.append("领域关键词").append(keywords).append("个 → ");
        sb.append("复杂度").append(complexity).append("/10, 建议").append(techCount).append("个技法");
        return sb.toString();
    }

    // ====== JSON提取（轻量级，不引入Jackson依赖） ======

    private int extractInt(String json, String key, int defaultVal) {
        try {
            String pattern = "\"" + key + "\"\\s*:\\s*(\\d+)";
            var m = java.util.regex.Pattern.compile(pattern).matcher(json);
            if (m.find()) return Integer.parseInt(m.group(1));
        } catch (Exception ignored) {}
        return defaultVal;
    }

    private String extractString(String json, String key, String defaultVal) {
        try {
            String pattern = "\"" + key + "\"\\s*:\\s*\"([^\"]+)\"";
            var m = java.util.regex.Pattern.compile(pattern).matcher(json);
            if (m.find()) return m.group(1);
        } catch (Exception ignored) {}
        return defaultVal;
    }

    private List<String> extractList(String json, String key) {
        try {
            String pattern = "\"" + key + "\"\\s*:\\s*\\[([^\\]]+)\\]";
            var m = java.util.regex.Pattern.compile(pattern).matcher(json);
            if (m.find()) {
                return Arrays.stream(m.group(1).split(","))
                        .map(s -> s.replaceAll("[\"\\s]", ""))
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toList());
            }
        } catch (Exception ignored) {}
        return List.of();
    }

    private List<String> merge(List<String> a, List<String> b) {
        Set<String> merged = new LinkedHashSet<>(a);
        merged.addAll(b);
        return new ArrayList<>(merged);
    }

    /**
     * 轻量关键词→技法推荐器（纯本地，复用TechniqueRecommender的部分逻辑但更轻量）
     */
    private class Recommender {
        // 预留扩展点：未来可接入TechniqueRecommender做更精准的推荐
    }
}
