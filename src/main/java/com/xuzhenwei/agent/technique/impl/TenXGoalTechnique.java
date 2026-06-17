package com.xuzhenwei.agent.technique.impl;

import com.xuzhenwei.agent.agent.AgentEvent;
import com.xuzhenwei.agent.agent.ConversationManager;
import com.xuzhenwei.agent.technique.Technique;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * 技法002 — 10倍目标
 *
 * <p>通过将目标提高10倍，迫使AI跳出常规逻辑进行破坏式创新。</p>
 */
@Component
public class TenXGoalTechnique implements Technique {

    private final ChatClient chatClient;
    private final ConversationManager conversationManager;

    public TenXGoalTechnique(ChatClient chatClient, ConversationManager conversationManager) {
        this.chatClient = chatClient;
        this.conversationManager = conversationManager;
    }

    @Override public String getId() { return "002"; }
    @Override public String getName() { return "10倍目标"; }
    @Override public String getCategory() { return "快速产生灵感"; }
    @Override public String getDescription() { return "将目标提高10倍，强制跳出线性思维，产生颠覆性创意"; }
    @Override public int getStepCount() { return 3; }

    @Override
    public Flux<AgentEvent> execute(String topic, String conversationId) {
        return Flux.concat(
                step1_SetExtremeGoal(topic, conversationId),
                step2_DescribeStates(topic, conversationId),
                step3_GenerateSolutions(topic, conversationId)
        );
    }

    private Flux<AgentEvent> step1_SetExtremeGoal(String topic, String conversationId) {
        int step = 1;
        String stepName = "设定10倍挑战目标";

        var prompt = """
                用户课题：%s

                请将上述课题的目标设定为原来的10倍，生成一个极具挑战性、甚至看似"荒谬"的新目标。
                用一句话清晰描述这个10倍目标。
                """.formatted(topic);

        return executeStep(step, stepName, prompt, conversationId);
    }

    private Flux<AgentEvent> step2_DescribeStates(String topic, String conversationId) {
        int step = 2;
        String stepName = "描述7种实现后的状态";

        var prompt = """
                基于上一步的10倍目标，请畅想：如果你真的达成了这个目标，
                世界会呈现怎样的状态？请描述 7 种具体、生动的状态或场景。
                每条用一两句话，要有画面感。
                """;

        return executeStep(step, stepName, prompt, conversationId);
    }

    private Flux<AgentEvent> step3_GenerateSolutions(String topic, String conversationId) {
        int step = 3;
        String stepName = "生成可执行创意方案";

        var prompt = """
                基于以上7种状态，回到原始课题「%s」：

                请提供 5 个具体、有魅力、可执行的创意方案。
                每个方案请包含：
                - 核心创意（一句话）
                - 关键执行步骤（2-3步）
                - 为什么这个方案能通向10倍目标
                """.formatted(topic);

        return executeStep(step, stepName, prompt, conversationId);
    }

    private Flux<AgentEvent> executeStep(int step, String stepName, String prompt, String convId) {
        return Flux.create(sink -> {
            sink.next(AgentEvent.stepStart(step, stepName, getId()));

            try {
                // 为每个技法创建独立的会话线
                String key = convId + "_" + getId();

                // 将之前步骤的内容作为上下文
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
                        .doOnError(sink::error)
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
