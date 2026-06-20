package com.xuzhenwei.agent.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 文本预处理管道 — v3.4
 *
 * <p>在所有输入进入技法推荐引擎之前，执行两层预处理：
 * <ol>
 *   <li>Phase 1（本地）: 分词 + 频次统计 + 编辑距离错别字检测 — 0 token, &lt;10ms</li>
 *   <li>Phase 2（智谱）: 纠错确认 + 同义词合并 + 意图提取 — ~300 token, 1-2s</li>
 * </ol>
 *
 * <p>输出 PreprocessResult（含 rawKeywords/correctedText/intentAnalysis），
 * 直接驱动下游 TechniqueRecommender。失败时降级为本地结果，不阻塞主流程。</p>
 *
 * <p>三张"逻辑表"用 JSON 三个 key 替代：rawKeywords(表1) + correctedText(清洗) + intentAnalysis(表2)</p>
 */
@Service
public class TextPreprocessor {

    private static final Logger log = LoggerFactory.getLogger(TextPreprocessor.class);

    private static final Set<String> STOP_WORDS = Set.of(
        "的","了","是","我","你","他","她","它","们","这","那","吗","呢","吧","啊","哦","嗯",
        "就","也","都","还","要","会","能","可以","应该","可能","已经","正在","一直",
        "因为","所以","但是","虽然","如果","而且","或者","和","与","及",
        "对","把","被","给","向","从","在","到","用","以","为",
        "很","太","更","最","非常","比较","有点","不","没","别","没有",
        "一个","一种","这个","那个","什么","怎么","为什么","怎么样","多少",
        "上","下","中","里","外","前","后","左","右","请","帮","让","告诉","说"
    );

    private static final Pattern SPLIT_PATTERN = Pattern.compile("[，。！？、；：\"\"''【】《》（）…—·\\s\n\r]+");

    private final ChatClient chatClient;
    private Set<String> commonWords = new HashSet<>();

    public TextPreprocessor(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @PostConstruct
    void loadWordList() {
        try {
            Path path = Path.of("src/main/resources/common_words.txt");
            if (!Files.exists(path)) {
                path = Path.of("target/classes/common_words.txt");
            }
            if (Files.exists(path)) {
                commonWords = new HashSet<>(Files.readAllLines(path, StandardCharsets.UTF_8));
                commonWords.removeIf(String::isBlank);
                log.info("加载基础词库: {} 词", commonWords.size());
            } else {
                log.warn("common_words.txt 未找到，错别字检测降级");
            }
        } catch (Exception e) {
            log.warn("加载词库失败: {}", e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 主入口
    // ═══════════════════════════════════════════════════════════

    public PreprocessResult process(String userInput, String fileContent) {
        String combined = userInput != null ? userInput : "";
        if (fileContent != null && !fileContent.isBlank()) {
            combined = fileContent + "\n---\n" + combined;
        }

        // Phase 1: 本地分析
        long t1 = System.currentTimeMillis();
        List<PreprocessResult.RawKeyword> rawKeywords = extractKeywords(combined, fileContent, userInput);
        long phase1Ms = System.currentTimeMillis() - t1;
        log.debug("Phase1 完成: {}个关键词, {}ms", rawKeywords.size(), phase1Ms);

        // 判断是否跳过 Phase 2
        if (shouldSkipPhase2(userInput, fileContent, rawKeywords)) {
            return localOnlyResult(rawKeywords, combined, phase1Ms);
        }

        // Phase 2: 智谱精炼
        long t2 = System.currentTimeMillis();
        try {
            ZhipuRefined refined = zhipuRefine(combined, rawKeywords);
            long phase2Ms = System.currentTimeMillis() - t2;
            log.info("Phase2 完成: intent={}, domains={}, {}ms",
                refined.intent, refined.domains, phase2Ms);
            return new PreprocessResult(rawKeywords, refined.correctedText,
                refined.toIntentAnalysis(), phase1Ms, phase2Ms);
        } catch (Exception e) {
            log.warn("智谱精炼失败，降级本地结果: {}", e.getMessage());
            return localOnlyResult(rawKeywords, combined, phase1Ms);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Phase 1: 本地分析
    // ═══════════════════════════════════════════════════════════

    List<PreprocessResult.RawKeyword> extractKeywords(String combinedText,
                                                       String fileContent, String userInput) {
        String[] words = SPLIT_PATTERN.split(combinedText);
        Map<String, Integer> freq = new LinkedHashMap<>();

        for (String w : words) {
            w = w.trim();
            if (w.isEmpty() || w.length() == 1 || w.length() > 8 || STOP_WORDS.contains(w)) continue;
            freq.merge(w, 1, Integer::sum);
        }

        if (freq.isEmpty()) return List.of();

        int maxFreq = freq.values().stream().mapToInt(Integer::intValue).max().orElse(1);
        List<PreprocessResult.RawKeyword> result = new ArrayList<>();
        int rank = 0;

        for (var e : freq.entrySet()) {
            boolean fromFile = isFromFile(e.getKey(), fileContent);
            double sourceBias = fromFile ? 0.15 : 0.0;
            double weight = Math.min(1.0, (double) e.getValue() / maxFreq + sourceBias);
            TypoCheck typo = checkTypo(e.getKey());

            result.add(new PreprocessResult.RawKeyword(
                e.getKey(), e.getValue(), weight,
                fromFile ? "file" : "user_input",
                typo.isTypo, typo.suggestion));

            if (++rank >= 20) break;
        }

        result.sort((a, b) -> Double.compare(b.weight(), a.weight()));
        return result;
    }

    private boolean isFromFile(String word, String fileContent) {
        return fileContent != null && !fileContent.isBlank() && fileContent.contains(word);
    }

    private TypoCheck checkTypo(String word) {
        if (word.length() < 2 || commonWords.isEmpty()) return new TypoCheck(false, null);
        if (commonWords.contains(word)) return new TypoCheck(false, null);

        String best = null;
        int bestDist = 99;
        for (String dict : commonWords) {
            if (Math.abs(dict.length() - word.length()) > 2) continue;
            int d = editDistance(word, dict);
            if (d < bestDist) { bestDist = d; best = dict; }
        }
        return (bestDist <= 2 && best != null)
            ? new TypoCheck(true, best) : new TypoCheck(false, null);
    }

    private record TypoCheck(boolean isTypo, String suggestion) {}

    /** 编辑距离（Levenshtein） */
    static int editDistance(String a, String b) {
        int m = a.length(), n = b.length();
        int[] prev = new int[n + 1], curr = new int[n + 1];
        for (int j = 0; j <= n; j++) prev[j] = j;
        for (int i = 1; i <= m; i++) {
            curr[0] = i;
            for (int j = 1; j <= n; j++) {
                curr[j] = (a.charAt(i - 1) == b.charAt(j - 1))
                    ? prev[j - 1]
                    : 1 + Math.min(Math.min(prev[j], curr[j - 1]), prev[j - 1]);
            }
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return prev[n];
    }

    // ═══════════════════════════════════════════════════════════
    // Phase 2: 智谱精炼
    // ═══════════════════════════════════════════════════════════

    private ZhipuRefined zhipuRefine(String text, List<PreprocessResult.RawKeyword> keywords) {
        StringBuilder kwSummary = new StringBuilder();
        for (var kw : keywords) {
            kwSummary.append(kw.word()).append("(权重").append(String.format("%.2f", kw.weight())).append(")");
            if (kw.suspectedTypo()) kwSummary.append("[疑似错别字→").append(kw.suggestion()).append("]");
            kwSummary.append(", ");
        }

        String summary = text.length() > 800 ? text.substring(0, 800) + "..." : text;

        String prompt = """
            你是一个文本预处理引擎。只返回JSON，不要任何解释。

            输入文本：
            %s

            候选关键词：
            %s

            请完成：
            1. 纠错：suspectedTypo=true的词，确认是否真的是错别字，给出正确写法
            2. 合并同义词：意思相近的词合并为统一表述
            3. 意图提取：用户真正想解决什么问题？隐含意图是什么？

            严格返回JSON格式（不要其他内容）：
            {"correctedText":"纠错后的完整文本","refinedKeywords":[{"word":"xx","weight":0.9}],
             "intent":"诊断分析|创意发想|方案打磨|执行落地","domains":["领域"],"coreQuestion":"核心问题一句话",
             "subQuestions":["子问题1"],"complexity":5}
            """.formatted(summary, kwSummary.toString());

        String response = chatClient.prompt()
            .user(prompt)
            .options(OpenAiChatOptions.builder()
                .model("glm-5.2")
                .maxTokens(300)
                .temperature(0.1)
                .build())
            .call()
            .content();

        return parseZhipuResponse(response);
    }

    private ZhipuRefined parseZhipuResponse(String json) {
        try {
            String correctedText = extractString(json, "correctedText", "");
            String intent = extractString(json, "intent", "诊断分析");
            List<String> domains = extractList(json, "domains");
            String coreQuestion = extractString(json, "coreQuestion", "");
            List<String> subQuestions = extractList(json, "subQuestions");
            int complexity = extractInt(json, "complexity", 5);
            return new ZhipuRefined(correctedText, intent, domains, coreQuestion, subQuestions, complexity);
        } catch (Exception e) {
            log.warn("解析智谱响应失败: {}", e.getMessage());
            throw new RuntimeException("智谱响应解析失败", e);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 辅助方法
    // ═══════════════════════════════════════════════════════════

    private boolean shouldSkipPhase2(String userInput, String fileContent,
                                      List<PreprocessResult.RawKeyword> keywords) {
        boolean hasFile = fileContent != null && !fileContent.isBlank();
        boolean hasTypos = keywords.stream().anyMatch(k -> k.suspectedTypo());
        int totalLen = (userInput != null ? userInput.length() : 0)
            + (fileContent != null ? fileContent.length() : 0);
        return !hasFile && !hasTypos && totalLen <= 20;
    }

    private PreprocessResult localOnlyResult(List<PreprocessResult.RawKeyword> keywords,
                                              String text, long phase1Ms) {
        var ia = new PreprocessResult.IntentAnalysis(
            "通用分析", List.of("通用分析"), text, List.of(), 3);
        return new PreprocessResult(keywords, text, ia, phase1Ms, 0);
    }

    private String extractString(String json, String key, String defaultVal) {
        try {
            var p = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"");
            var m = p.matcher(json);
            return m.find() ? m.group(1) : defaultVal;
        } catch (Exception ignored) { return defaultVal; }
    }

    private int extractInt(String json, String key, int defaultVal) {
        try {
            var p = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*(\\d+)");
            var m = p.matcher(json);
            return m.find() ? Integer.parseInt(m.group(1)) : defaultVal;
        } catch (Exception ignored) { return defaultVal; }
    }

    private List<String> extractList(String json, String key) {
        try {
            var p = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*\\[([^\\]]+)\\]");
            var m = p.matcher(json);
            if (m.find()) {
                return Arrays.stream(m.group(1).split(","))
                    .map(s -> s.replaceAll("[\"\\s]", ""))
                    .filter(s -> !s.isEmpty())
                    .toList();
            }
        } catch (Exception ignored) {}
        return List.of();
    }

    // ═══════════════════════════════════════════════════════════
    // 静态嵌套数据类
    // ═══════════════════════════════════════════════════════════

    /**
     * 预处理管道输出 — 三张逻辑表用三个字段替代。
     * rawKeywords=表1(原始关键词), correctedText=清洗后文本, intentAnalysis=表2(AI深度分析)
     */
    public record PreprocessResult(
        List<RawKeyword> rawKeywords,
        String correctedText,
        IntentAnalysis intentAnalysis,
        long phase1Ms,
        long phase2Ms
    ) {
        public record RawKeyword(
            String word, int frequency, double weight,
            String source, boolean suspectedTypo, String suggestion
        ) {}

        public record IntentAnalysis(
            String intent, List<String> domains,
            String coreQuestion, List<String> subQuestions, int complexity
        ) {}
    }

    private record ZhipuRefined(
        String correctedText, String intent, List<String> domains,
        String coreQuestion, List<String> subQuestions, int complexity
    ) {
        PreprocessResult.IntentAnalysis toIntentAnalysis() {
            return new PreprocessResult.IntentAnalysis(intent, domains, coreQuestion, subQuestions, complexity);
        }
    }
}
