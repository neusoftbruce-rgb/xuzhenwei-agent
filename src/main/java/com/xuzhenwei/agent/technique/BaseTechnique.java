package com.xuzhenwei.agent.technique;

import com.xuzhenwei.agent.agent.AgentEvent;
import com.xuzhenwei.agent.agent.ConversationManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import reactor.core.publisher.Flux;

/**
 * 技法抽象基类 — 提取所有技法共用的流式执行逻辑
 *
 * <p>每条技法只需要提供：编号、中文名、分类、描述、Prompt 模板。
 * 多步骤流式执行和上下文管理全部由基类统一处理。</p>
 */
public abstract class BaseTechnique implements Technique {

    private static final Logger log = LoggerFactory.getLogger(BaseTechnique.class);

    protected final ChatClient chatClient;
    protected final ConversationManager conversationManager;

    protected BaseTechnique(ChatClient chatClient, ConversationManager conversationManager) {
        this.chatClient = chatClient;
        this.conversationManager = conversationManager;
    }

    /**
     * 执行单个步骤，返回流式事件
     *
     * @param step       步骤号（从1开始）
     * @param stepName   步骤名称（显示给用户）
     * @param prompt     Prompt 文本
     * @param convId     会话 ID
     * @return 流式 AgentEvent
     */
    protected Flux<AgentEvent> executeStep(int step, String stepName, String prompt, String convId) {
        return Flux.create(sink -> {
            sink.next(AgentEvent.stepStart(step, stepName, getId()));

            try {
                String key = convId + "_" + getId();
                String history = conversationManager.formatHistory(key);
                String fullPrompt = history.isEmpty() ? prompt : history + "\n\n" + prompt;

                StringBuilder fullResponse = new StringBuilder();

                chatClient.prompt()
                        .user(fullPrompt)
                        .stream()
                        .content()
                        .doOnComplete(() -> {
                            conversationManager.append(key, "AI", fullResponse.toString());
                            sink.next(AgentEvent.stepComplete(step, getId()));
                            sink.complete();
                        })
                        .doOnError(e -> {
                            log.error("步骤{}执行异常", step, e);
                            sink.next(AgentEvent.error("步骤%d异常: %s".formatted(step, e.getMessage())));
                            sink.complete();
                        })
                        .subscribe(chunk -> {
                            fullResponse.append(chunk);
                            sink.next(AgentEvent.stepContent(step, chunk, getId()));
                        });

            } catch (Exception e) {
                sink.next(AgentEvent.error("步骤%d异常: %s".formatted(step, e.getMessage())));
                sink.complete();
            }
        });
    }
}
