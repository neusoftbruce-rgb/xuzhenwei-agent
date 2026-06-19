package com.xuzhenwei.agent.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * 响应精炼器 — 把 AI 原始输出整理成干净、易读、不跑题的回复
 *
 * <p>流程：
 * <ol>
 *   <li>AI 生成原始内容</li>
 *   <li>精炼器二次处理：去废话、统一格式、加标题层级、标重点</li>
 *   <li>输出给用户</li>
 * </ol>
 */
@Service
public class ResponseRefiner {

    private static final Logger log = LoggerFactory.getLogger(ResponseRefiner.class);

    private final ChatClient chatClient;

    public ResponseRefiner(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * 精炼 AI 原始输出
     *
     * @param originalContent 原始AI输出
     * @param userQuestion    用户原始问题（用于判断是否答非所问）
     * @return 精炼后的内容
     */
    public String refine(String originalContent, String userQuestion) {
        if (originalContent == null || originalContent.isBlank()) return "";

        String prompt = """
                你是文字编辑专家。请把下面这段AI生成的回答整理成一份专业报告。

                【用户原始问题】
                %s

                【AI原始回答】
                %s

                整理要求：
                1. 第一行必须是报告标题，格式：# 报告标题（根据内容提炼，不超过20字）
                2. 标题下面空一行，用 > 写一句核心结论（不超过40字，直击要害）
                3. 用 ## 和 ### 分层，层级清晰
                4. 关键数字和结论用 **加粗**
                5. 列表用 - 分点，每点不超过2行
                6. 删除重复啰嗦的内容
                7. 删除与用户问题无关的内容
                8. 保留所有核心信息和创意
                9. 如果发现回答偏题了，标注"⚠️ 注意：以下部分可能与你的问题不完全匹配"
                10. 总长度控制在原文的50-70%%，不要扩写

                直接输出整理后的内容，不要加"以下是整理后的..."之类的开场白。
                """.formatted(userQuestion, originalContent);

        try {
            String refined = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();
            // 追加真实生成时间
            String timestamp = java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            return refined + "\n\n*📅 生成时间：" + timestamp + "*";
        } catch (Exception e) {
            log.error("精炼失败，返回原始内容", e);
            return originalContent; // fallback
        }
    }

    /**
     * 轻量整理——只排版不缩内容（用于流式输出的每一段）
     */
    public String lightRefine(String content) {
        if (content == null || content.isBlank()) return "";

        // 1. 确保中英文之间有空格
        content = content.replaceAll("([\\u4e00-\\u9fa5])([A-Za-z])", "$1 $2");
        content = content.replaceAll("([A-Za-z])([\\u4e00-\\u9fa5])", "$1 $2");

        // 2. 确保列表符号后有空
        content = content.replaceAll("(?m)^([-•·])(\\S)", "$1 $2");

        // 3. 确保标点后换行合理
        content = content.replaceAll("(?m)([。！？])(\\S)", "$1\n$2");

        return content;
    }
}
