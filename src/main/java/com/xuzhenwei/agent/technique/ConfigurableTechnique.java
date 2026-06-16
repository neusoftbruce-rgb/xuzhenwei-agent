package com.xuzhenwei.agent.technique;

import com.xuzhenwei.agent.agent.AgentEvent;
import com.xuzhenwei.agent.agent.ConversationManager;
import org.springframework.ai.chat.client.ChatClient;
import reactor.core.publisher.Flux;

/**
 * 可配置技法 — 从 YAML 读入定义，动态执行
 *
 * <p>这是整个系统的核心设计：56条技法不需要56个Java类，
 * 一个通用执行器 + YAML配置 = 全部技法。</p>
 */
public class ConfigurableTechnique extends BaseTechnique {

    private final TechniqueFactory.TechniqueDef definition;

    public ConfigurableTechnique(TechniqueFactory.TechniqueDef definition,
                                 ChatClient chatClient,
                                 ConversationManager conversationManager) {
        super(chatClient, conversationManager);
        this.definition = definition;
    }

    @Override public String getId() { return definition.getId(); }
    @Override public String getName() { return definition.getName(); }
    @Override public String getCategory() { return definition.getCategory(); }
    @Override public String getDescription() { return definition.getDescription(); }
    @Override public int getStepCount() { return definition.getStepCount(); }

    @Override
    public Flux<AgentEvent> execute(String topic, String conversationId) {
        var steps = definition.getSteps();
        if (steps == null || steps.isEmpty()) {
            return Flux.just(AgentEvent.error("技法 " + getId() + " 没有配置步骤"));
        }

        // 串联执行所有步骤
        Flux<AgentEvent> result = Flux.empty();
        for (int i = 0; i < steps.size(); i++) {
            final int stepNum = i + 1;
            var step = steps.get(i);
            String prompt = step.getPrompt().replace("{topic}", topic);

            result = result.concatWith(
                    executeStep(stepNum, step.getName(), prompt, conversationId)
            );
        }
        return result;
    }
}
