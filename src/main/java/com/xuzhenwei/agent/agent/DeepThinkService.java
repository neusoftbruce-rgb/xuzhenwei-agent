package com.xuzhenwei.agent.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

/**
 * 深度思考服务 — 使用 DeepSeek Reasoner (R1)
 *
 * <p>两阶段：
 * <ol>
 *   <li>用 deepseek-reasoner 深度推理 → 显示推理过程给用户</li>
 *   <li>用 deepseek-chat 整理成易读最终答案</li>
 * </ol>
 */
@Service
public class DeepThinkService {

    private static final Logger log = LoggerFactory.getLogger(DeepThinkService.class);

    private final ChatClient chatClient;
    private static final String REASONER_MODEL = "deepseek-reasoner";

    public DeepThinkService(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * 深度思考结果
     */
    public record DeepThinkResult(
            String reasoning,    // 推理过程
            String finalAnswer   // 最终回答
    ) {}

    /**
     * 深度思考
     */
    public DeepThinkResult deepThink(String userQuestion, String context) {
        long start = System.currentTimeMillis();

        // 第1步：深度推理
        log.info("🧠 深度推理中...");
        String thinkingPrompt = """
                请对以下问题进行深度分析和推理：

                【问题】
                %s

                %s

                请从以下维度进行推理：
                1. 问题的本质是什么？
                2. 关键因素有哪些？
                3. 可能的解决方案（至少3个），各自的优劣？
                4. 最优路径和理由？
                5. 第一步行动建议？
                """.formatted(userQuestion,
                context != null && !context.isBlank() ? "【背景】\n" + context : "");

        String reasoning = chatClient.prompt()
                .user(thinkingPrompt)
                .options(OpenAiChatOptions.builder()
                        .model(REASONER_MODEL)
                        .maxTokens(8000)
                        .build())
                .call()
                .content();

        long reasoningTime = System.currentTimeMillis() - start;
        log.info("🧠 推理完成 ({}ms, {}字)", reasoningTime, reasoning.length());

        // 第2步：整理成易读最终答案
        String polishPrompt = """
                你是徐振伟，农业培训顾问。

                请把以下深度分析整理成用户容易读的回答。

                【用户问题】
                %s

                【深度分析】
                %s

                要求：
                - 通俗易懂，不要学术腔
                - 用清晰的小标题分层
                - 关键结论 **加粗**
                - 具体建议用列表
                - 开头用 > 一句话总结核心结论
                - 不要太长，保留核心内容
                """.formatted(userQuestion, reasoning);

        String finalAnswer = chatClient.prompt()
                .user(polishPrompt)
                .call()
                .content();

        log.info("✅ 深度思考总耗时: {}ms", System.currentTimeMillis() - start);
        return new DeepThinkResult(reasoning, finalAnswer);
    }
}
