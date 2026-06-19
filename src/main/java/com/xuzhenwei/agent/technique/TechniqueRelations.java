package com.xuzhenwei.agent.technique;

import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 技法关系图谱 — v3.0 方法8
 *
 * <p>定义技法之间的前后置关系，让推荐不只是"单条推荐"，
 * 而是"推荐一条思考路径"。</p>
 *
 * <p>关系类型：
 * <ul>
 *   <li>NEXT — 执行完 A 后推荐执行 B</li>
 *   <li>COMPLEMENT — A 和 B 互补，可组合使用</li>
 *   <li>VERIFY — B 可以用来验证 A 的输出</li>
 * </ul>
 */
@Component
public class TechniqueRelations {

    public enum RelationType { NEXT, COMPLEMENT, VERIFY }

    public record Relation(String fromId, String toId, RelationType type, String reason) {}

    /** 所有技法关系定义 */
    private static final List<Relation> RELATIONS = List.of(
            // === 创意发散路径 ===
            new Relation("003", "005", RelationType.NEXT, "半成品激发→专家会诊：先用不完美方案激活思路，再用多角色发散"),
            new Relation("007", "024", RelationType.NEXT, "关联词发散→一句话展开：100个词中找到角度，展开成大纲"),
            new Relation("002", "025", RelationType.NEXT, "10倍目标→6W3H：打开格局后，用结构化框架落地"),
            new Relation("001", "023", RelationType.COMPLEMENT, "跨界联想+跨界模式：从不同领域借两个维度的灵感"),

            // === 方案打磨路径 ===
            new Relation("025", "031", RelationType.NEXT, "6W3H→魔鬼审阅：结构化方案后挑刺找漏洞"),
            new Relation("025", "027", RelationType.VERIFY, "6W3H→可行性打分：框架搭好后客观评估"),
            new Relation("019", "025", RelationType.NEXT, "亮点挖掘→6W3H：先赞美保护创造力，再结构化完善"),
            new Relation("026", "034", RelationType.NEXT, "精益画布→财务预测：商业模式画好后算账"),
            new Relation("030", "038", RelationType.NEXT, "风险扫描→行动计划：排完雷后做执行计划"),

            // === 执行落地路径 ===
            new Relation("034", "035", RelationType.NEXT, "财务预测→客户获取：算清账后找客户"),
            new Relation("035", "038", RelationType.NEXT, "客户获取→行动计划：找到客户后制定执行方案"),
            new Relation("036", "035", RelationType.COMPLEMENT, "视频脚本+客户获取：内容营销配合获客策略"),
            new Relation("037", "038", RelationType.NEXT, "最小验证→行动计划：验证可行后全面铺开"),

            // === 诊断分析路径 ===
            new Relation("040", "044", RelationType.NEXT, "障碍根因→烦恼拆解：找到根因后拆成可处理的小问题"),
            new Relation("044", "045", RelationType.NEXT, "烦恼拆解→根源挖掘：拆完后继续深挖"),
            new Relation("045", "030", RelationType.NEXT, "根源挖掘→风险扫描：找到根源后预判风险"),
            new Relation("042", "054", RelationType.COMPLEMENT, "案例调研+趋势扫描：看别人的经验+看未来的趋势"),

            // === 验证闭环 ===
            new Relation("031", "027", RelationType.COMPLEMENT, "魔鬼审阅+可行性打分：双保险验证"),
            new Relation("038", "031", RelationType.VERIFY, "行动计划→魔鬼审阅：执行前最后挑一次刺"),
            new Relation("034", "050", RelationType.COMPLEMENT, "财务预测+满意度提升：算钱+算满意度双维度"),

            // === TIPS 路径 ===
            new Relation("TIPS-01", "TIPS-02", RelationType.NEXT, "思绪整理→摘要提取：理清后提取重点"),
            new Relation("TIPS-14", "TIPS-24", RelationType.NEXT, "学习计划→习题集：计划制定后出题巩固"),
            new Relation("TIPS-24", "TIPS-27", RelationType.NEXT, "习题集→自我对话：刷题后内化知识"),
            new Relation("TIPS-33", "TIPS-38", RelationType.COMPLEMENT, "周报PDCA+思维链：复盘时用CoT深度分析"),
            new Relation("TIPS-43", "TIPS-02", RelationType.NEXT, "语音笔记→摘要：说完后提取结构化笔记"),
            new Relation("TIPS-44", "TIPS-07", RelationType.NEXT, "隐喻魔术师→专业翻译：比喻讲通后翻译成专业语言")
    );

    /** 快速索引: fromId → 所有后继关系 */
    private final Map<String, List<Relation>> fromIndex = new HashMap<>();

    public TechniqueRelations() {
        for (var r : RELATIONS) {
            fromIndex.computeIfAbsent(r.fromId, k -> new ArrayList<>()).add(r);
        }
        // 双向索引：建立反向查找
        for (var r : RELATIONS) {
            fromIndex.computeIfAbsent(r.toId, k -> new ArrayList<>());
        }
    }

    /**
     * 获取某个技法之后推荐执行的技法（NEXT 或 COMPLEMENT 类型）
     */
    public List<Relation> getNextSteps(String techniqueId) {
        return fromIndex.getOrDefault(techniqueId, List.of()).stream()
                .filter(r -> r.type == RelationType.NEXT || r.type == RelationType.COMPLEMENT)
                .toList();
    }

    /**
     * 获取可以用来验证某个技法输出的技法（VERIFY 类型）
     */
    public List<Relation> getVerifiers(String techniqueId) {
        return fromIndex.getOrDefault(techniqueId, List.of()).stream()
                .filter(r -> r.type == RelationType.VERIFY)
                .toList();
    }

    /**
     * 获取与某个技法互补的技法
     */
    public List<Relation> getComplements(String techniqueId) {
        return fromIndex.getOrDefault(techniqueId, List.of()).stream()
                .filter(r -> r.type == RelationType.COMPLEMENT)
                .toList();
    }

    /**
     * 根据技法ID生成推荐路径（含理由）
     */
    public List<TechniqueRecommender.TechniqueSuggestion> suggestPath(String techniqueId,
                                                                       TechniqueRegistry registry) {
        var nextSteps = getNextSteps(techniqueId);
        if (nextSteps.isEmpty()) return List.of();

        List<TechniqueRecommender.TechniqueSuggestion> suggestions = new ArrayList<>();
        for (var step : nextSteps) {
            var tech = registry.get(step.toId);
            tech.ifPresent(t -> suggestions.add(new TechniqueRecommender.TechniqueSuggestion(
                    t.getId(), t.getName(), 0.65,
                    "🔄 " + step.reason)));
        }
        return suggestions;
    }

    /** 关系总数 */
    public int relationCount() { return RELATIONS.size(); }
}
