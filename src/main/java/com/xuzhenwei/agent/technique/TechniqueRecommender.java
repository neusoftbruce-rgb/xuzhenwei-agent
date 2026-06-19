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
    private final RecommendationDecisionEngine decisionEngine;

    public TechniqueRecommender(TechniqueRegistry registry,
                                DomainAdvisor domainAdvisor,
                                ChatClient chatClient,
                                RecommendationDecisionEngine decisionEngine) {
        this.registry = registry;
        this.domainAdvisor = domainAdvisor;
        this.chatClient = chatClient;
        this.decisionEngine = decisionEngine;
    }

    /**
     * 根据用户输入推荐技法（快速匹配模式）
     */
    public RecommendationResult recommend(String userInput) {
        return recommend(userInput, false);
    }

    /**
     * 根据用户输入推荐技法（支持随机打乱，让"换一批"不重复）
     */
    public RecommendationResult recommend(String userInput, boolean shuffle) {
        String input = userInput.toLowerCase();

        // 0. 问题复杂度分类（纯本地，0ms）
        var level = decisionEngine.classify(userInput);

        // 1. 先看是否匹配领域知识库中的业务板块
        var domainMatch = domainAdvisor.matchBusiness(userInput);

        // 2. 关键词 → 技法映射（动态截断，不再硬编码 limit(9)）
        var matches = keywordMatch(input, shuffle);

        // 3. 匹配技法组合配方
        var recipe = matchRecipe(input);

        // 4. 构建推荐结果（携带复杂度元数据）
        if (domainMatch.isPresent()) {
            var suggestions = matches.isEmpty() ? domainMatch.get().suggestedTechniques.stream()
                    .map(r -> new TechniqueSuggestion(r.id(), r.reason(), 0.8))
                    .toList() : matches.stream().map(MatchResult::toSuggestion).toList();
            return new RecommendationResult(suggestions, recipe,
                    "已识别你的业务：「%s」。推荐以下技法和配方：".formatted(domainMatch.get().name),
                    level, suggestions.size());
        }

        if (!matches.isEmpty()) {
            var suggestions = matches.stream().map(MatchResult::toSuggestion).toList();
            return new RecommendationResult(suggestions, recipe,
                    "根据你的问题，推荐以下技法：", level, suggestions.size());
        }

        // 默认：用"半成品激发法"开场
        var defaultSuggestion = List.of(new TechniqueSuggestion("003", "半成品激发法", 0.6,
                "这是最好的开场技法——先让AI出几个不完美的方案，激发你的灵感"));
        return new RecommendationResult(defaultSuggestion, null,
                "试试「半成品激发法」开场，它能让AI先给你一些不完美的灵感：",
                level, 1);
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
        return keywordMatch(input, false);
    }

    private List<MatchResult> keywordMatch(String input, boolean shuffle) {
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
        keywordMap.put("没有灵感", match("007", 0.8, "关联词发散法（含随机词碰撞），让AI的100个词砸中你的灵感"));
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

        // === AI独学50TIPS（v2.1：纳入自动推荐）===
        keywordMap.put("整理思绪", match("TIPS-01", 0.85, "通过与AI对话，把混乱思绪梳理清楚、分级"));
        keywordMap.put("太乱了", match("TIPS-01", 0.8, "让AI帮你把杂乱思绪分类整理"));
        keywordMap.put("摘要", match("TIPS-02", 0.85, "AI快速提取长篇文章核心观点"));
        keywordMap.put("太长不看", match("TIPS-02", 0.85, "让AI帮你几秒内读完长文"));
        keywordMap.put("新闻", match("TIPS-03", 0.8, "自动化每日新闻查阅，AI帮你筛选重点"));
        keywordMap.put("会议纪要", match("TIPS-04", 0.9, "让AI成为会议纪要撰写大师"));
        keywordMap.put("会议记录", match("TIPS-04", 0.9, "让AI自动整理会议记录"));
        keywordMap.put("写文档", match("TIPS-06", 0.8, "通过AI批改克服文档写作恐惧"));
        keywordMap.put("翻译", match("TIPS-07", 0.85, "专业术语转化为易懂语言"));
        keywordMap.put("做PPT", match("TIPS-08", 0.9, "Word瞬间转PPT，AI帮你排版"));
        keywordMap.put("PPT", match("TIPS-08", 0.85, "把文字内容快速转成PPT幻灯片"));
        keywordMap.put("配图", match("TIPS-09", 0.8, "AI生成PPT插图提升资料易懂度"));
        keywordMap.put("笔记", match("TIPS-10", 0.85, "移动中灵感→结构化笔记"));
        keywordMap.put("灵感记录", match("TIPS-10", 0.85, "碎片灵感自动整理成结构化笔记"));
        keywordMap.put("陪练", match("TIPS-11", 0.85, "AI陪练激发创新灵感"));
        keywordMap.put("学习计划", match("TIPS-14", 0.9, "AI生涯设计师定制学习计划"));
        keywordMap.put("学习路径", match("TIPS-14", 0.85, "AI为你量身定制学习路线图"));
        keywordMap.put("任务分解", match("TIPS-15", 0.85, "全自动倒推任务分解(WBS)"));
        keywordMap.put("拆任务", match("TIPS-15", 0.85, "把大目标自动拆成可执行小任务"));
        keywordMap.put("坚持不下去", match("TIPS-16", 0.85, "预测学习绊脚石防半途而废"));
        keywordMap.put("半途而废", match("TIPS-16", 0.85, "AI提前预警你会在哪里放弃"));
        keywordMap.put("学习风格", match("TIPS-17", 0.8, "学习风格诊断定制策略"));
        keywordMap.put("有声书", match("TIPS-20", 0.8, "专业书→专属有声书"));
        keywordMap.put("YouTube", match("TIPS-21", 0.8, "YouTube视频→文本教材"));
        keywordMap.put("难懂", match("TIPS-22", 0.85, "提取难懂文献要点"));
        keywordMap.put("看不懂", match("TIPS-22", 0.85, "AI帮你把难懂的文献变简单"));
        keywordMap.put("刷题", match("TIPS-24", 0.85, "个性化习题集填补知识漏洞"));
        keywordMap.put("考试", match("TIPS-25", 0.85, "自动分析真题推导合格最短路径"));
        keywordMap.put("真题", match("TIPS-25", 0.85, "AI分析真题告诉你考什么"));
        keywordMap.put("专注", match("TIPS-19", 0.85, "召唤计时员AI保持专注"));
        keywordMap.put("分心", match("TIPS-19", 0.8, "AI帮你对抗分心保持学习节奏"));
        keywordMap.put("NotebookLM", match("TIPS-23", 0.85, "NotebookLM私有知识库高效学习"));
        keywordMap.put("WBS", match("TIPS-15", 0.8, "全自动任务分解"));
        keywordMap.put("晚间", match("TIPS-26", 0.85, "晚间5分钟AI对话巩固学习"));
        keywordMap.put("复习", match("TIPS-26", 0.8, "每天5分钟AI帮你固化白天学的内容"));
        keywordMap.put("自己对话", match("TIPS-27", 0.85, "与自己分身对话内化知识"));
        keywordMap.put("内化", match("TIPS-27", 0.8, "通过AI分身对话把知识变成自己的"));
        keywordMap.put("练口语", match("TIPS-28", 0.9, "AI外教角色扮演练口语"));
        keywordMap.put("英语口语", match("TIPS-28", 0.85, "AI当外教陪你练口语"));
        keywordMap.put("英文邮件", match("TIPS-29", 0.85, "AI撰写商务英文邮件"));
        keywordMap.put("英文", match("TIPS-29", 0.8, "AI帮你写出地道的商务英文"));
        keywordMap.put("动力", match("TIPS-30", 0.85, "夸奖型AI教练科学维持学习动力"));
        keywordMap.put("没动力", match("TIPS-30", 0.85, "AI当你的专属夸奖教练"));
        keywordMap.put("奖状", match("TIPS-31", 0.8, "自动颁发数字奖状激励进步"));
        keywordMap.put("游戏化", match("TIPS-32", 0.85, "游戏化学习进度让学习上瘾"));
        keywordMap.put("打卡", match("TIPS-32", 0.8, "把学习变成游戏闯关"));
        keywordMap.put("周报", match("TIPS-33", 0.9, "自动生成周报快速PDCA循环"));
        keywordMap.put("PDCA", match("TIPS-33", 0.85, "AI帮你做每周PDCA复盘"));
        keywordMap.put("社群", match("TIPS-34", 0.85, "AI社群经理协同成长"));
        keywordMap.put("概念图", match("TIPS-35", 0.85, "概念图鸟瞰思考全景"));
        keywordMap.put("思维导图", match("TIPS-35", 0.85, "AI生成概念图帮你理清思路"));
        keywordMap.put("深度研究", match("TIPS-36", 0.9, "深度研究深挖特定课题"));
        keywordMap.put("挖深", match("TIPS-36", 0.85, "AI帮你深挖一个课题不浮于表面"));
        keywordMap.put("对比", match("TIPS-37", 0.85, "多新闻对比可视化事实与观点"));
        keywordMap.put("多角度看", match("TIPS-37", 0.8, "AI帮你对比多个信息源辨别真伪"));
        keywordMap.put("思维链", match("TIPS-38", 0.9, "思维链(CoT)获取高精度回答"));
        keywordMap.put("CoT", match("TIPS-38", 0.9, "用思维链让AI回答更精准"));
        keywordMap.put("恶魔", match("031", 0.85, "魔鬼审阅法——让AI扮演最挑剔的评审，找出所有漏洞"));
        keywordMap.put("反驳我", match("031", 0.85, "魔鬼审阅法——让AI故意和你唱反调帮你完善想法"));
        keywordMap.put("替代方案", match("TIPS-40", 0.9, "替代方案生成器制定最优解"));
        keywordMap.put("还有别的办法吗", match("TIPS-40", 0.85, "AI生成多个替代方案帮你对比"));
        keywordMap.put("计划书", match("TIPS-41", 0.9, "AI投资人将构思升华为计划书"));
        keywordMap.put("BP", match("TIPS-41", 0.85, "AI帮你把想法写成投资人能懂的计划书"));
        keywordMap.put("投资", match("TIPS-41", 0.8, "AI模拟投资人帮你打磨项目计划书"));
        keywordMap.put("旧稿", match("TIPS-42", 0.85, "解析旧作把握认知偏差"));
        keywordMap.put("回顾", match("TIPS-42", 0.8, "AI分析你的旧作品发现你的成长轨迹"));
        keywordMap.put("语音笔记", match("TIPS-43", 0.9, "灵感语音→调研到框架瞬间输出"));
        keywordMap.put("说灵感", match("TIPS-43", 0.85, "说出来就行，AI帮你把语音灵感变成文章框架"));
        keywordMap.put("隐喻", match("TIPS-44", 0.9, "隐喻魔术师拆解难懂概念"));
        keywordMap.put("打个比方", match("TIPS-44", 0.85, "让AI用比喻把复杂概念变简单"));
        keywordMap.put("图解", match("TIPS-45", 0.85, "图解直观理解科学抽象概念"));
        keywordMap.put("画图", match("TIPS-45", 0.8, "AI帮你把抽象概念画成容易理解的图"));
        keywordMap.put("知识库", match("TIPS-46", 0.9, "构建个人知识库第二大脑"));
        keywordMap.put("第二大脑", match("TIPS-46", 0.85, "用AI搭建你的个人知识管理系统"));
        keywordMap.put("职场", match("TIPS-47", 0.85, "借助AI猎头掌握职场竞争力"));
        keywordMap.put("竞争力", match("TIPS-47", 0.85, "AI帮你分析职场竞争力短板"));
        keywordMap.put("3年规划", match("TIPS-48", 0.9, "从未来预测倒推3年生涯战略"));
        keywordMap.put("生涯", match("TIPS-48", 0.85, "用未来倒推法规划你的职业生涯"));
        keywordMap.put("找书", match("TIPS-49", 0.85, "读书DNA遇见命运之书"));
        keywordMap.put("推荐书", match("TIPS-49", 0.85, "AI根据你的读书DNA推荐最适合你的书"));
        keywordMap.put("超输出", match("TIPS-50", 0.9, "体得AI时代超输出思考法"));
        keywordMap.put("高效输出", match("TIPS-50", 0.85, "掌握AI时代的超高效内容输出方法"));

        // 逐个匹配，按技法ID去重（同ID保留最高分）
        Map<String, MatchResult> seen = new LinkedHashMap<>();
        for (var entry : keywordMap.entrySet()) {
            if (input.contains(entry.getKey())) {
                var m = entry.getValue();
                var existing = seen.get(m.techniqueId);
                if (existing == null || m.score > existing.score) {
                    seen.put(m.techniqueId, m);
                }
            }
        }
        List<MatchResult> results = new ArrayList<>(seen.values());

        // 排序、智能截断（已在上游按技法ID去重）
        var sorted = results.stream()
                .sorted((a, b) -> Double.compare(b.score, a.score))
                .collect(java.util.stream.Collectors.toList());

        // 动态截断：根据问题复杂度确定推荐数量
        var level = decisionEngine.classify(input);
        int cutoff = decisionEngine.determineCutoff(sorted, level);
        var topResults = sorted.stream().limit(cutoff).collect(java.util.stream.Collectors.toList());

        // shuffle时：打乱顺序 + 补充探索性技法（也受复杂度上限约束）
        if (shuffle && !topResults.isEmpty()) {
            java.util.Collections.shuffle(topResults, new java.util.Random());
            var explore = List.of(
                match("003", 0.4, "半成品激发法——换个角度，让AI给你不完美的灵感"),
                match("007", 0.35, "关联词+随机词双模发散——换个角度碰撞出新火花"),
                match("001", 0.35, "跨界碰撞法——从完全不相关的领域借灵感"),
                match("005", 0.4, "虚拟专家会诊——让8种角色同时帮你想"),
                match("001", 0.35, "跨界特征联想法——从动物植物身上找灵感"),
                match("054", 0.3, "最新趋势扫描——看看外面世界在发生什么"),
                match("046", 0.3, "烦恼抽象化——把具体烦恼变成通用问题")
            );
            var rnd = new java.util.Random();
            java.util.Collections.shuffle(explore, rnd);
            for (var ex : explore) {
                if (topResults.size() >= level.getMaxRecommend()) break;
                boolean exists = topResults.stream().anyMatch(s -> s.techniqueId.equals(ex.techniqueId));
                if (!exists) topResults.add(ex);
            }
        }
        return topResults;
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
                    List.of("007", "024", "036"),
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
            ),
            // ---- 5条农业混合工作流 (v4.0) ----
            new TechniqueRecipe(
                    "农技推广：发现翻译传播",
                    List.of("农技", "技术推广", "农技推广", "农业技术", "种植技术"),
                    List.of("054", "TIPS-44", "TIPS-07", "036"),
                    "趋势扫描找技术 → 翻译成易懂内容 → 深度输出 → 视频传播"
            ),
            new TechniqueRecipe(
                    "课程开发：策划验证迭代",
                    List.of("课程开发", "课程设计", "教学", "培训课程", "开发课程"),
                    List.of("025", "029", "037", "027", "TIPS-33"),
                    "6W3H策划 → 利益方预演 → 最小验证 → 打分评估 → 解构知识"
            ),
            new TechniqueRecipe(
                    "农场诊断：把脉开方跟诊",
                    List.of("农场", "种植", "大棚", "温室", "作物", "病害", "土壤"),
                    List.of("005", "040", "022", "038", "TIPS-19"),
                    "专家会诊 → 根因分析 → 方案联姻 → 行动计划 → 输出打磨"
            ),
            new TechniqueRecipe(
                    "农业品牌：洞察定位内容",
                    List.of("农业品牌", "品牌打造", "农产品品牌", "差异化"),
                    List.of("047", "048", "051", "023", "001"),
                    "用户调研 → 不满清单 → 核心需求提炼 → 跨界模式 → 跨界联想"
            ),
            new TechniqueRecipe(
                    "学员养成：学练考用闭环",
                    List.of("学员", "教学管理", "学习路径", "养成", "培训效果"),
                    List.of("TIPS-14", "TIPS-24", "TIPS-27", "037"),
                    "目标图像化 → 难度分级 → 输出闭环 → 最小验证"
            )
    );

    // === 数据类 ===

    public record RecommendationResult(
            List<TechniqueSuggestion> suggestions,
            TechniqueRecipe recipe,
            String explanation,
            RecommendationDecisionEngine.ComplexityLevel complexity,
            int recommendCount
    ) {
        public RecommendationResult(List<TechniqueSuggestion> suggestions,
                                     TechniqueRecipe recipe,
                                     String explanation) {
            this(suggestions, recipe, explanation,
                    RecommendationDecisionEngine.ComplexityLevel.SINGLE_DOMAIN,
                    suggestions.size());
        }
    }

    public record TechniqueSuggestion(String techniqueId, String techniqueName, double confidence, String reason) {
        public TechniqueSuggestion(String techniqueId, String techniqueName, double confidence) {
            this(techniqueId, techniqueName, confidence, "");
        }
    }

    record MatchResult(String techniqueId, String techniqueName, double score, String reason)
            implements RecommendationDecisionEngine.ScoredItem {
        // score() 由 record 自动生成，匹配 ScoredItem.score()
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
