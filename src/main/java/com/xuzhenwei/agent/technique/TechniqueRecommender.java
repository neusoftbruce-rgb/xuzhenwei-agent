package com.xuzhenwei.agent.technique;

import com.xuzhenwei.agent.agent.DomainAdvisor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 技法智能推荐器 — 分析用户输入，自动推荐最合适的技法或技法组合
 *
 * <p>两种推荐模式：
 * <ol>
 *   <li>关键词快速匹配（毫秒级，不消耗API）</li>
 *   <li>LLM深度分析（慢但精准）</li>
 * </ol>
 */
@Service
public class TechniqueRecommender {

    private static final Logger log = LoggerFactory.getLogger(TechniqueRecommender.class);

    private final TechniqueRegistry registry;
    private final DomainAdvisor domainAdvisor;
    private final ChatClient chatClient;

    public TechniqueRecommender(TechniqueRegistry registry,
                                DomainAdvisor domainAdvisor,
                                ChatClient chatClient) {
        this.registry = registry;
        this.domainAdvisor = domainAdvisor;
        this.chatClient = chatClient;
    }

    /**
     * 根据用户输入推荐技法（快速匹配模式）
     *
     * @return 推荐结果：单条技法、技法组合、或让用户选择
     */
    public RecommendationResult recommend(String userInput) {
        String input = userInput.toLowerCase();

        // 1. 先看是否匹配领域知识库中的业务板块
        var domainMatch = domainAdvisor.matchBusiness(userInput);

        // 2. 关键词 → 技法映射
        var matches = keywordMatch(input);

        // 3. 匹配对应的技法组合配方
        var recipe = matchRecipe(input);

        // 4. 构建推荐结果
        if (domainMatch.isPresent()) {
            // 有业务匹配 → 返回该业务的预配置技法建议 + 可能配方
            return new RecommendationResult(
                    matches.isEmpty() ? domainMatch.get().suggestedTechniques.stream()
                            .map(r -> new TechniqueSuggestion(r.id(), r.reason(), 0.8))
                            .toList() : matches.stream().map(m -> m.toSuggestion()).toList(),
                    recipe,
                    "已识别你的业务：「%s」。推荐以下技法和配方：".formatted(domainMatch.get().name)
            );
        }

        if (!matches.isEmpty()) {
            return new RecommendationResult(
                    matches.stream().map(MatchResult::toSuggestion).toList(),
                    recipe,
                    "根据你的问题，推荐以下技法："
            );
        }

        // 默认：用"半成品激发法"开场
        return new RecommendationResult(
                List.of(new TechniqueSuggestion("003", "半成品激发法", 0.6,
                        "这是最好的开场技法——先让AI出几个不完美的方案，激发你的灵感")),
                null,
                "试试「半成品激发法」开场，它能让AI先给你一些不完美的灵感："
        );
    }

    /**
     * 用 LLM 深度分析推荐（更精准但慢）
     */
    public String deepRecommend(String userInput) {
        try {
            var techniques = registry.getAll();
            StringBuilder techList = new StringBuilder();
            for (var t : techniques) {
                techList.append("[%s] %s — %s\n".formatted(t.getId(), t.getName(), t.getDescription()));
            }

            var prompt = """
                    用户问题：%s

                    可用技法列表：
                    %s

                    请从以上技法中选出最适合用户问题的1-3条技法，并说明理由。
                    如果有2条以上技法适合串联使用，请说明建议的执行顺序。
                    格式：先推荐技法+理由，再说明串联方案。

                    请直接给出建议，不要客套。
                    """.formatted(userInput, techList.toString());

            return chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

        } catch (Exception e) {
            log.error("LLM推荐失败，回退关键词匹配", e);
            return recommend(userInput).explanation();
        }
    }

    /** 关键词 → 技法快速匹配 */
    private List<MatchResult> keywordMatch(String input) {
        List<MatchResult> results = new ArrayList<>();

        // 场景关键词 → 最适合的技法
        Map<String, MatchResult> keywordMap = new LinkedHashMap<>();

        // === 没头绪 / 找灵感 ===
        keywordMap.put("完全没思路", match("001", 0.9, "完全没有头绪时，「跨界特征联想法」帮你从动物身上找灵感"));
        keywordMap.put("不知道怎么做", match("003", 0.9, "先让AI出几个半成品方案，激发你的思路"));
        keywordMap.put("脑暴", match("005", 0.85, "8位虚拟专家同时给你出主意"));
        keywordMap.put("头脑风暴", match("005", 0.85, "8位虚拟专家是你的免费智囊团"));
        keywordMap.put("怎么开始", match("003", 0.85, "99%的情况，先来一剂「半成品激发法」"));

        // === 需要大量点子 ===
        keywordMap.put("大量创意", match("007", 0.85, "关联词发散100个词，穷尽所有角度"));
        keywordMap.put("没有灵感", match("009", 0.8, "随机词碰撞法，让AI的100个随机词砸中你的灵感"));
        keywordMap.put("出点子", match("007", 0.8, "关联词发散法是你的点子工厂"));
        keywordMap.put("创新", match("002", 0.85, "10倍目标挑战法，逼出颠覆性创意"));

        // === 打磨方案 ===
        keywordMap.put("完善方案", match("025", 0.9, "用6W3H框架把模糊想法变成完整策划案"));
        keywordMap.put("方案不完整", match("025", 0.85, "6W3H帮你把漏洞全补上"));
        keywordMap.put("验证", match("027", 0.85, "可行性打分法，让AI当你的评审官"));
        keywordMap.put("可行性", match("027", 0.85, "可行性打分法，客观评估你的方案"));
        keywordMap.put("怕出错", match("031", 0.9, "魔鬼审阅法——在别人挑刺之前先自己挑一遍"));
        keywordMap.put("挑刺", match("031", 0.9, "魔鬼审阅法，免费的严厉老板"));
        keywordMap.put("找漏洞", match("031", 0.9, "魔鬼审阅法会找出你方案里每一个弱点"));
        keywordMap.put("风险", match("030", 0.85, "风险扫描法，提前排雷"));
        keywordMap.put("原创性", match("032", 0.85, "原创查重，避免跟别人撞车"));
        keywordMap.put("商业模式", match("026", 0.9, "精益画布法，把创意画成商业蓝图"));
        keywordMap.put("完善", match("019", 0.8, "亮点挖掘法——先赞美再改进，避免创造力被打击"));

        // === 执行落地 ===
        keywordMap.put("执行", match("038", 0.9, "行动计划生成法，把想法变成待办清单"));
        keywordMap.put("怎么落地", match("038", 0.9, "行动计划生成法，帮你拆解每一步"));
        keywordMap.put("行动计划", match("038", 0.9, "行动计划生成法，一步到位"));
        keywordMap.put("赚钱", match("034", 0.85, "18个月财务预测，先算清楚账"));
        keywordMap.put("财务", match("034", 0.85, "18个月财务预测，三档（保守/中等/乐观）"));
        keywordMap.put("获客", match("035", 0.9, "第一批客户获取法，解决最难的从0到1"));
        keywordMap.put("推广", match("035", 0.85, "第一批客户获取法，先把种子用户搞到手"));
        keywordMap.put("营销", match("035", 0.85, "第一批客户获取法，精准找到愿意买单的人"));
        keywordMap.put("视频", match("036", 0.9, "30秒视频脚本生成，直接给你分镜"));
        keywordMap.put("短视频", match("036", 0.9, "30秒视频脚本生成，拍视频不用愁脚本"));
        keywordMap.put("验证实验", match("037", 0.85, "最小验证法，先花小钱试试，成了再All in"));
        keywordMap.put("试水", match("037", 0.85, "最小验证法，先小规模跑通再说"));

        // === 分析问题 ===
        keywordMap.put("烦恼", match("044", 0.8, "烦恼拆解法，把大烦恼拆成小问题"));
        keywordMap.put("困惑", match("045", 0.8, "烦恼根源挖掘法，找到真正的问题"));
        keywordMap.put("用户不满意", match("048", 0.85, "不满清单法，站在顾客角度找问题"));
        keywordMap.put("趋势", match("054", 0.85, "最新趋势扫描法，别错过风口"));
        keywordMap.put("未来", match("015", 0.85, "10年倒推法，从未来回看今天"));
        keywordMap.put("竞争对手", match("042", 0.8, "国内外案例调研法，看看别人怎么做的"));
        keywordMap.put("定价", match("050", 0.8, "全方位满意度提升法，从价格到体验全优化"));
        keywordMap.put("品牌", match("023", 0.85, "跨界商业模式借鉴，看看隔壁行业怎么玩的"));

        // 逐个匹配
        for (var entry : keywordMap.entrySet()) {
            if (input.contains(entry.getKey())) {
                results.add(entry.getValue());
            }
        }

        // 去重、按score排序、取前3
        return results.stream()
                .distinct()
                .sorted((a, b) -> Double.compare(b.score, a.score))
                .limit(3)
                .toList();
    }

    /** 匹配技法组合配方 */
    private TechniqueRecipe matchRecipe(String input) {
        for (var recipe : RECIPES) {
            for (var keyword : recipe.keywords) {
                if (input.contains(keyword)) {
                    return recipe;
                }
            }
        }
        return null;
    }

    private MatchResult match(String id, double score, String reason) {
        var tech = registry.get(id).orElse(null);
        if (tech == null) return new MatchResult(id, "未知技法", score, reason);
        return new MatchResult(id, tech.getName(), score, reason);
    }

    // ---- 预定义的技法组合配方 ----

    private static final List<TechniqueRecipe> RECIPES = List.of(
            new TechniqueRecipe(
                    "创业验证套餐",
                    List.of("创业", "新业务", "新项目", "启动", "开始做"),
                    List.of("002", "026", "034", "031"),
                    "先10倍目标打开格局 → 精益画布画蓝图 → 财务预测算账 → 魔鬼审阅排雷"
            ),
            new TechniqueRecipe(
                    "内容创作套餐",
                    List.of("选题", "内容", "短视频", "抖音", "文案", "脚本"),
                    List.of("009", "024", "036"),
                    "随机词碰撞找选题 → 一句话展开成大纲 → 30秒脚本直接拍"
            ),
            new TechniqueRecipe(
                    "产品定价套餐",
                    List.of("定价", "价格", "卖多少钱", "收费"),
                    List.of("034", "050", "027"),
                    "财务预测算成本 → 满意度提升定价值 → 可行性评分验证"
            ),
            new TechniqueRecipe(
                    "客户获取套餐",
                    List.of("客户", "获客", "引流", "招生", "推广"),
                    List.of("035", "048", "053"),
                    "第一批客户获取策略 → 客户不满清单找痛点 → 理想用户画像精准定位"
            ),
            new TechniqueRecipe(
                    "战略规划套餐",
                    List.of("战略", "规划", "方向", "转型", "明路"),
                    List.of("005", "015", "030", "038"),
                    "虚拟专家多角度 → 10年倒推找方向 → 风险扫描排雷 → 行动计划落地"
            ),
            new TechniqueRecipe(
                    "提案打磨套餐",
                    List.of("提案", "汇报", "方案", "策划", "策划案"),
                    List.of("025", "031", "033"),
                    "6W3H结构化 → 魔鬼审阅挑刺 → 新闻稿视角升华"
            ),
            new TechniqueRecipe(
                    "农业品牌套餐",
                    List.of("酵素菌", "西红柿", "番茄", "农产品", "品牌"),
                    List.of("001", "005", "028", "036"),
                    "跨界特征找差异化 → 虚拟专家多角度 → 自身优势匹配 → 视频脚本传播"
            ),
            new TechniqueRecipe(
                    "深度诊断套餐",
                    List.of("诊断", "根源", "为什么", "根因", "深层", "本质"),
                    List.of("040", "044", "045", "030"),
                    "障碍根因分析 → 烦恼拆解 → 烦恼根源挖掘 → 风险扫描"
            )
    );

    // === 数据类 ===

    public record RecommendationResult(
            List<TechniqueSuggestion> suggestions,
            TechniqueRecipe recipe,
            String explanation
    ) {}

    public record TechniqueSuggestion(String techniqueId, String techniqueName, double confidence, String reason) {
        public TechniqueSuggestion(String techniqueId, String techniqueName, double confidence) {
            this(techniqueId, techniqueName, confidence, "");
        }
    }

    record MatchResult(String techniqueId, String techniqueName, double score, String reason) {
        TechniqueSuggestion toSuggestion() {
            return new TechniqueSuggestion(techniqueId, techniqueName, score, reason);
        }
    }

    public record TechniqueRecipe(
            String name,
            List<String> keywords,
            List<String> techniqueIds,
            String description
    ) {}
}
