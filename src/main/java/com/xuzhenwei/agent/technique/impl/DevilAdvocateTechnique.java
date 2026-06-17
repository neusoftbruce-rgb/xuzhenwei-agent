package com.xuzhenwei.agent.technique.impl;

import com.xuzhenwei.agent.agent.AgentEvent;
import com.xuzhenwei.agent.agent.ConversationManager;
import com.xuzhenwei.agent.technique.Technique;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * 技法031 — 挑刺模拟（ダメ出しシミュレーション）
 *
 * <p>模拟严厉的上司/审阅者视角，对策划案进行全方位挑刺。
 * 提案前的"压力测试"——被 AI 挑过的方案，在真人面前更扛得住。</p>
 */
@Component
public class DevilAdvocateTechnique implements Technique {

    private final ChatClient chatClient;
    private final ConversationManager conversationManager;

    public DevilAdvocateTechnique(ChatClient chatClient, ConversationManager conversationManager) {
        this.chatClient = chatClient;
        this.conversationManager = conversationManager;
    }

    @Override public String getId() { return "031"; }
    @Override public String getName() { return "挑刺模拟"; }
    @Override public String getCategory() { return "打磨完善创意"; }
    @Override public String getDescription() { return "模拟严厉的审阅者视角，在正式提案前找出所有薄弱环节"; }
    @Override public int getStepCount() { return 2; }

    @Override
    public Flux<AgentEvent> execute(String topic, String conversationId) {
        return Flux.concat(
                step1_DevilAdvocate(topic, conversationId),
                step2_Improve(topic, conversationId)
        );
    }

    private Flux<AgentEvent> step1_DevilAdvocate(String topic, String conversationId) {
        int step = 1;
        String stepName = "魔鬼审阅者挑刺";

        var prompt = """
                你是一位极其严厉的企业高管，以"挑剔"著称。
                现在有一份策划案/创意方案摆在你面前：

                「%s」

                请从以下角度无情挑刺：
                1. **逻辑漏洞**：哪里有因果链断裂？
                2. **资源可行性**：预算、人力、时间哪里不现实？
                3. **市场风险**：竞争对手会如何反击？用户凭什么买单？
                4. **执行障碍**：落地过程最可能卡在哪一步？
                5. **隐藏假设**：方案基于哪些未经验证的假设？

                语气要像真实的严厉上司——直接、不留情面，但要言之有物。
                每个挑刺点请具体说明"为什么是问题"。
                """.formatted(topic);

        return executeStep(step, stepName, prompt, conversationId);
    }

    private Flux<AgentEvent> step2_Improve(String topic, String conversationId) {
        int step = 2;
        String stepName = "逐一应对改进";

        var prompt = """
                基于以上挑刺意见，请为策划案「%s」提供改进方案。

                要求：
                - 把每一个"不可行"的理由，转化为"已解决"的改进点
                - 给出具体的修改建议，不是泛泛而谈
                - 优先解决最致命的 3 个问题
                - 最后给出改进后的方案摘要
                """.formatted(topic);

        return executeStep(step, stepName, prompt, conversationId);
    }

    private Flux<AgentEvent> executeStep(int step, String stepName, String prompt, String convId) {
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
