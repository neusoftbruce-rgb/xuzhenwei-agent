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
 * 复杂查询自动拆解服务 — v3.0 方法4
 *
 * <p>当用户输入超过 100 字或包含多个问号时，自动将大问题拆成 2-4 个子问题，
 * 每个子问题独立推荐技法，并行或串行执行。</p>
 *
 * <p>架构参考：
 * <ul>
 *   <li>MA-RAG (ACL 2025): 查询 → Planner → Step Definer → Extractor → QA</li>
 *   <li>UniRAG (EMNLP 2025): 实体锚定拆解 → 子事实验证 → 迭代重写</li>
 * </ul>
 *
 * <p>Token 消耗：~300 tokens/次（拆解 Prompt + 输出）</p>
 */
@Service
public class QueryDecompositionService {

    private static final Logger log = LoggerFactory.getLogger(QueryDecompositionService.class);

    private final ChatClient chatClient;

    /** 触发拆解的最小字数 */
    private static final int MIN_LENGTH_FOR_DECOMP = 80;
    /** 触发拆解的最小问号数 */
    private static final int MIN_QUESTIONS_FOR_DECOMP = 2;

    public QueryDecompositionService(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * 拆解结果
     */
    public record DecompositionResult(
            boolean shouldDecompose,         // 是否需要拆解
            String reasoning,                 // 拆解理由
            List<SubQuestion> subQuestions    // 子问题列表
    ) {
        public boolean hasSubQuestions() {
            return subQuestions != null && !subQuestions.isEmpty();
        }
    }

    /**
     * 子问题
     */
    public record SubQuestion(
            int index,           // 序号，1-based
            String question,     // 子问题文本
            String focus         // 分析侧重（如"定价维度"、"获客维度"）
    ) {}

    // ═══════════════════════════════════════════════════════════
    // 公开 API
    // ═══════════════════════════════════════════════════════════

    /**
     * 判断是否需要拆解，如果需要则调用模型拆解。
     */
    public DecompositionResult analyze(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return new DecompositionResult(false, "空输入", List.of());
        }

        int length = userInput.length();
        int questionCount = countChar(userInput, '?') + countChar(userInput, '？');

        // 判断是否需要拆解
        boolean shouldDecomp = length > MIN_LENGTH_FOR_DECOMP
                || questionCount >= MIN_QUESTIONS_FOR_DECOMP
                || hasDecompKeywords(userInput);

        if (!shouldDecomp) {
            return new DecompositionResult(false,
                    "输入较短(%d字/%d问号)，无需拆解".formatted(length, questionCount),
                    List.of());
        }

        // 调用模型拆解
        try {
            var subs = decompose(userInput);
            return new DecompositionResult(true,
                    "长文本(%d字/%d问号)，拆解为%d个子问题".formatted(length, questionCount, subs.size()),
                    subs);
        } catch (Exception e) {
            log.warn("查询拆解失败: {}", e.getMessage());
            return new DecompositionResult(false, "拆解失败，按单一问题处理", List.of());
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 模型拆解
    // ═══════════════════════════════════════════════════════════

    private List<SubQuestion> decompose(String userInput) {
        // 限制输入长度（最多前800字）
        String shortInput = userInput.length() > 800
                ? userInput.substring(0, 800) + "..."
                : userInput;

        String prompt = """
                请将以下用户问题拆解成2-4个独立的子问题。每个子问题应该是可以独立分析的具体问题。

                用户问题：
                %s

                拆解规则：
                1. 每个子问题聚焦一个维度（如定价、获客、风险、执行等）
                2. 子问题之间不重叠，但合起来覆盖原始问题的全部关注点
                3. 如果用户问题包含多个问号，每个问号至少对应一个子问题

                严格按以下JSON格式输出（不要其他内容）：
                {"subs":[{"index":1,"question":"子问题1","focus":"定价维度"},{"index":2,"question":"子问题2","focus":"获客维度"}]}
                """.formatted(shortInput);

        String response = chatClient.prompt()
                .user(prompt)
                .options(OpenAiChatOptions.builder()
                        .model("glm-5.2")
                        .maxTokens(400)
                        .temperature(0.2)
                        .build())
                .call()
                .content();

        return parseDecompositionResponse(response);
    }

    /** 解析模型返回的拆解 JSON — v3.0 fix: 字段顺序灵活匹配 */
    private List<SubQuestion> parseDecompositionResponse(String json) {
        List<SubQuestion> subs = new ArrayList<>();
        try {
            // 匹配每个子问题对象，字段顺序任意
            var objPattern = Pattern.compile("\\{[^}]+\\}");
            var indexPat = Pattern.compile("\"index\"\\s*:\\s*(\\d+)");
            var questionPat = Pattern.compile("\"question\"\\s*:\\s*\"([^\"]+)\"");
            var focusPat = Pattern.compile("\"focus\"\\s*:\\s*\"([^\"]+)\"");

            var objMatcher = objPattern.matcher(json);
            while (objMatcher.find()) {
                String obj = objMatcher.group();
                var im = indexPat.matcher(obj);
                var qm = questionPat.matcher(obj);
                var fm = focusPat.matcher(obj);
                if (im.find() && qm.find() && fm.find()) {
                    subs.add(new SubQuestion(
                            Integer.parseInt(im.group(1)),
                            qm.group(1),
                            fm.group(1)
                    ));
                }
            }
        } catch (Exception e) {
            log.warn("解析拆解响应失败: {}", e.getMessage());
        }
        return subs;
    }

    // ═══════════════════════════════════════════════════════════
    // 辅助方法
    // ═══════════════════════════════════════════════════════════

    private int countChar(String text, char c) {
        int count = 0;
        for (char ch : text.toCharArray()) {
            if (ch == c) count++;
        }
        return count;
    }

    /** 检查是否包含拆解触发关键词 */
    private boolean hasDecompKeywords(String input) {
        String lower = input.toLowerCase();
        // 多个"怎么"/"如何" → 多问题
        int howCount = 0;
        for (String kw : List.of("怎么", "如何", "怎样", "为什么", "什么是")) {
            int idx = 0;
            while ((idx = lower.indexOf(kw, idx)) != -1) {
                howCount++;
                idx += kw.length();
            }
        }
        return howCount >= 3;
    }
}
