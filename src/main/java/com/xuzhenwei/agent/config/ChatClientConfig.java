package com.xuzhenwei.agent.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ChatClient 配置 — 使用 Spring AI 自动配置的 ChatModel
 *
 * <p>DeepSeek API 连接参数在 application.yml 中配置，
 * Spring AI 自动创建 OpenAiChatModel Bean，
 * 这里包装为统一入口 ChatClient。</p>
 */
@Configuration
public class ChatClientConfig {

    /**
     * ChatClient — 所有 AI 调用的统一入口
     *
     * <p>Spring AI 会自动注入配置好的 ChatModel：
     * 如果有 spring-ai-starter-model-openai 就是 OpenAiChatModel (用于 DeepSeek)
     */
    @Bean
    public ChatClient chatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }
}
