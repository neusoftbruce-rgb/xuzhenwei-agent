package com.xuzhenwei.agent.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 智能追问服务 — v3.0 方法7
 *
 * <p>根据已执行的技法和 AI 输出内容，生成上下文相关的追问建议，
 * 取代之前随机 4 选 1 的追问方式。</p>
 *
 * <p>Token 消耗：~100 tokens/次（极小 Prompt + 极小输出）</p>
 *
 * <p>两种模式：
 * <ol>
 *   <li>快速模式（本地，0 Token）：基于关键词匹配追问模板</li>
 *   <li>精准模式（智谱5.2，~100 Token）：根据实际输出内容生成追问</li>
 * </ol>
 */
@Service
public class FollowupService {

    private static final Logger log = LoggerFactory.getLogger(FollowupService.class);

    private final ChatClient chatClient;

    public FollowupService(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * 追问建议
     */
    public record FollowupSuggestions(
            List<FollowupQuestion> questions,
            boolean fromModel   // true=模型生成, false=模板
    ) {}

    public record FollowupQuestion(String text, String quickAction) {}

    // ═══════════════════════════════════════════════════════════
    // 公开 API
    // ═══════════════════════════════════════════════════════════

    /**
     * 根据用户问题和 AI 回复生成追问建议。
     *
     * <p>先用快速模板（0 Token），如果匹配不到合适的则调模型。</p>
     */
    public FollowupSuggestions generate(String userQuestion, String aiResponse) {
        // 阶段1: 快速模板匹配（0 Token）
        var templateResult = templateMatch(userQuestion, aiResponse);
        if (!templateResult.questions().isEmpty()) {
            return templateResult;
        }

        // 阶段2: 模型生成（~100 Token）
        try {
            return modelGenerate(userQuestion, aiResponse);
        } catch (Exception e) {
            log.warn("追问生成失败，回退默认: {}", e.getMessage());
            return defaultFollowups();
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 阶段1: 快速模板（0 Token）
    // ═══════════════════════════════════════════════════════════

    private FollowupSuggestions templateMatch(String question, String response) {
        String lower = question.toLowerCase();
        List<FollowupQuestion> qs = new ArrayList<>();

        // 财务类 → 追问敏感性分析 + 对比
        if (containsAny(lower, "钱", "财务", "成本", "定价", "收入", "利润", "赚")) {
            qs.add(new FollowupQuestion("要做敏感性分析吗？看看乐观/悲观情况", "敏感性分析"));
            qs.add(new FollowupQuestion("要对比同行的定价/成本数据吗？", "对比同行"));
        }
        // 推广/获客类 → 追问渠道 + 转化率
        else if (containsAny(lower, "推广", "获客", "营销", "引流", "招生", "客户")) {
            qs.add(new FollowupQuestion("要设计具体的获客渠道组合吗？", "渠道策略"));
            qs.add(new FollowupQuestion("要预估转化率和获客成本吗？", "获客成本预算"));
        }
        // 战略/规划类 → 追问时间线 + 风险
        else if (containsAny(lower, "战略", "规划", "方向", "转型", "未来")) {
            qs.add(new FollowupQuestion("要不倒推一个3年实施路线图？", "3年路线图"));
            qs.add(new FollowupQuestion("要识别关键风险和应对策略吗？", "风险扫描"));
        }
        // 方案打磨类 → 追问魔鬼审阅 + 行动计划
        else if (containsAny(lower, "方案", "策划", "提案", "设计")) {
            qs.add(new FollowupQuestion("要用魔鬼审阅法挑一下漏洞吗？", "魔鬼审阅挑刺"));
            qs.add(new FollowupQuestion("要转化成具体行动计划吗？", "行动计划"));
        }
        // 诊断/问题类 → 追问根因 + 解决方案
        else if (containsAny(lower, "为什么", "原因", "问题", "失败", "诊断")) {
            qs.add(new FollowupQuestion("要深挖一层，找根本原因吗？", "根因分析"));
            qs.add(new FollowupQuestion("要看看别人遇到类似问题怎么解决的吗？", "案例调研"));
        }

        return new FollowupSuggestions(qs, false);
    }

    // ═══════════════════════════════════════════════════════════
    // 阶段2: 模型生成（~100 Token）
    // ═══════════════════════════════════════════════════════════

    private FollowupSuggestions modelGenerate(String question, String response) {
        // 只取回复的前200字作为上下文
        String shortResponse = response != null
                ? response.substring(0, Math.min(200, response.length()))
                : "";

        String prompt = """
                基于以下对话，生成2个追问建议（用户接下来可能想深入的方向）。
                只输出JSON数组，不要其他内容。

                用户问题：%s
                AI回复摘要：%s

                输出格式：
                [{"text":"追问内容","action":"快捷按钮文字(≤8字)"}]
                """.formatted(
                        question.substring(0, Math.min(100, question.length())),
                        shortResponse
                );

        String result = chatClient.prompt()
                .user(prompt)
                .options(OpenAiChatOptions.builder()
                        .model("glm-5.2")
                        .maxTokens(150)
                        .temperature(0.3)
                        .build())
                .call()
                .content();

        return parseModelResponse(result);
    }

    /** 解析模型返回的 JSON 追问数组 */
    private FollowupSuggestions parseModelResponse(String json) {
        List<FollowupQuestion> qs = new ArrayList<>();
        try {
            var textPattern = Pattern.compile("\"text\"\\s*:\\s*\"([^\"]+)\"");
            var actionPattern = Pattern.compile("\"action\"\\s*:\\s*\"([^\"]+)\"");

            var textMatcher = textPattern.matcher(json);
            var actionMatcher = actionPattern.matcher(json);

            List<String> texts = new ArrayList<>();
            List<String> actions = new ArrayList<>();

            while (textMatcher.find()) texts.add(textMatcher.group(1));
            while (actionMatcher.find()) actions.add(actionMatcher.group(1));

            for (int i = 0; i < Math.min(texts.size(), actions.size()); i++) {
                qs.add(new FollowupQuestion(texts.get(i), actions.get(i)));
            }
        } catch (Exception e) {
            log.warn("解析追问JSON失败: {}", e.getMessage());
        }

        if (qs.isEmpty()) {
            return defaultFollowups();
        }
        return new FollowupSuggestions(qs, true);
    }

    /** 默认追问（兜底） */
    private FollowupSuggestions defaultFollowups() {
        return new FollowupSuggestions(List.of(
                new FollowupQuestion("要不要深入分析其中某个点？", "深入分析"),
                new FollowupQuestion("需要转化成具体行动计划吗？", "行动计划")
        ), false);
    }

    private boolean containsAny(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }
}
