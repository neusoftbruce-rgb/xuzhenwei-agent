package com.xuzhenwei.agent.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * 咨询式分析服务 — v2.4 新增
 *
 * <p>参考 McKinsey Pyramid Principle + MECE 框架：
 * <ol>
 *   <li>全景快扫 (Quick Scan): 快速模型 → 结构化MECE分析, ~1500 tokens</li>
 *   <li>按需深挖 (Deep Dive): 用户选择维度 → 深度推理, ~2000 tokens</li>
 * </ol>
 *
 * <p>Token 消耗对比：传统深度思考 8000+ tokens/次 vs 咨询模式 1500 + 2000*n tokens</p>
 */
@Service
public class ConsultingAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(ConsultingAnalysisService.class);

    private final ChatClient chatClient;
    private final DeepThinkService deepThinkService;

    public ConsultingAnalysisService(ChatClient chatClient, DeepThinkService deepThinkService) {
        this.chatClient = chatClient;
        this.deepThinkService = deepThinkService;
    }

    /** 全景快扫系统提示词 — 基于 McKinsey Pyramid Principle */
    private static final String QUICK_SCAN_PROMPT = """
            你是顶级战略咨询公司(McKinsey/BCG)的分析师。请对用户问题做结构化快扫分析。

            输出格式(严格遵守):
            ## 📊 执行摘要
            > 一句话核心结论

            ## 🔍 分析维度(MECE)
            ### 维度1: [名称]
            - 核心发现（一句话）
            - 关键点（1-2条）

            ### 维度2: [名称]
            - 核心发现（一句话）
            - 关键点（1-2条）

            ### 维度3: [名称]
            - 核心发现（一句话）
            - 关键点（1-2条）

            (根据需要3-5个维度,确保不重不漏)

            ## 💡 初步建议
            - 最优先的1-2个行动
            - 哪些维度需要深入分析（标注⚠️）

            要求: 总字数600-800字, 结论先行, 每个维度不超过3个要点, 用MECE原则。
            """;

    /** 深挖系统提示词 */
    private static final String DEEP_DIVE_PROMPT = """
            你是顶级咨询公司的行业专家。请针对以下具体维度做深度分析。

            【原始问题】%s
            【分析维度】%s
            【快扫发现】%s

            请从以下角度深入:
            1. 根因分析 - 为什么会有这个问题?
            2. 量化影响 - 影响多大?怎么衡量?
            3. 解决方案 - 3个具体方案,各有利弊
            4. 第一步行动 - 明天就可以做什么?

            要求: 结构清晰, 用数据说话, 具体可执行。500-800字。
            """;

    /**
     * 全景快扫 — 用快速模型生成结构化MECE分析
     *
     * @param userQuestion 用户问题
     * @return SSE流式事件
     */
    public Flux<AgentEvent> quickScan(String userQuestion, String conversationId) {
        return Flux.create(sink -> {
            try {
                long start = System.currentTimeMillis();

                sink.next(AgentEvent.stepStart(1, "📊 全景快扫中...", "consulting-scan"));
                sink.next(AgentEvent.stepContent(1,
                        "🏛️ **咨询式分析** | 快扫阶段 · 快速模型 · 预计消耗 ~1500 tokens\n\n",
                        "consulting-scan"));

                String fullPrompt = QUICK_SCAN_PROMPT + "\n\n【用户问题】\n" + userQuestion;

                var stream = chatClient.prompt()
                        .user(fullPrompt)
                        .options(OpenAiChatOptions.builder()
                                .model("glm-5.2")  // 智谱5.2 (统一模型, 1M上下文)
                                .maxTokens(2000)
                                .temperature(0.3)  // 低温度=更聚焦
                                .build())
                        .stream()
                        .content();

                StringBuilder fullResponse = new StringBuilder();

                stream.doOnComplete(() -> {
                            // 追加维度操作提示
                            String footer = "\n\n---\n💡 **点击上方任意「维度」标题后的 🔍深挖 按钮，对该维度进行深度分析**\n";
                            sink.next(AgentEvent.stepContent(1, footer, "consulting-scan"));
                            long elapsed = System.currentTimeMillis() - start;
                            sink.next(AgentEvent.stepContent(1,
                                    "\n*⏱ 快扫耗时 %.1f秒 · 🪙 预估Token: ~%d*"
                                            .formatted(elapsed / 1000.0, fullResponse.length() / 2),
                                    "consulting-scan"));
                            sink.next(AgentEvent.stepComplete(1, "consulting-scan"));
                            sink.complete();
                        })
                        .doOnError(e -> {
                            log.error("快扫异常", e);
                            sink.next(AgentEvent.error("快扫失败: " + e.getMessage()));
                            sink.complete();
                        })
                        .subscribe(chunk -> {
                            fullResponse.append(chunk);
                            sink.next(AgentEvent.stepContent(1, chunk, "consulting-scan"));
                        });

            } catch (Exception e) {
                log.error("ConsultingAnalysisService error", e);
                sink.next(AgentEvent.error(e.getMessage()));
                sink.complete();
            }
        });
    }

    /**
     * 按需深挖 — 对用户选择的维度进行深度分析
     *
     * @param originalQuestion 用户原始问题
     * @param dimension        用户选择的维度
     * @param scanFindings     快扫阶段该维度的发现
     * @return SSE流式事件
     */
    public Flux<AgentEvent> deepDive(String originalQuestion, String dimension, String scanFindings) {
        return Flux.create(sink -> {
            try {
                long start = System.currentTimeMillis();

                sink.next(AgentEvent.stepStart(2, "🔬 深挖: " + dimension, "consulting-deep"));
                sink.next(AgentEvent.stepContent(2,
                        "🔬 **深度分析: " + dimension + "**\n\n",
                        "consulting-deep"));

                String divePrompt = DEEP_DIVE_PROMPT.formatted(originalQuestion, dimension,
                        scanFindings != null ? scanFindings : "（无快扫发现）");

                // 用深度推理模型
                var result = deepThinkService.deepThink(divePrompt, "");

                String reasoning = result.reasoning();
                if (reasoning != null && !reasoning.isBlank()) {
                    sink.next(AgentEvent.stepContent(2, "### 🧠 推理过程\n\n", "consulting-deep"));
                    String truncated = reasoning.length() > 1000 ?
                            reasoning.substring(0, 1000) + "\n\n...(推理截断)" : reasoning;
                    sink.next(AgentEvent.stepContent(2, truncated + "\n\n", "consulting-deep"));
                }

                sink.next(AgentEvent.stepContent(2, "### 📝 分析结论\n\n", "consulting-deep"));
                String finalAnswer = result.finalAnswer();
                if (finalAnswer != null) {
                    sink.next(AgentEvent.stepContent(2, finalAnswer, "consulting-deep"));
                }

                long elapsed = System.currentTimeMillis() - start;
                sink.next(AgentEvent.stepContent(2,
                        "\n\n---\n*⏱ 深挖耗时 %.1f秒 · 🧠 深度推理模式*".formatted(elapsed / 1000.0),
                        "consulting-deep"));
                sink.next(AgentEvent.stepComplete(2, "consulting-deep"));
                sink.complete();

            } catch (Exception e) {
                log.error("深挖异常", e);
                sink.next(AgentEvent.error("深挖失败: " + e.getMessage()));
                sink.complete();
            }
        });
    }
}
