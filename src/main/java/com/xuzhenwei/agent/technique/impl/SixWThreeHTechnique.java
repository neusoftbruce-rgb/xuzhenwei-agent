package com.xuzhenwei.agent.technique.impl;

import com.xuzhenwei.agent.agent.AgentEvent;
import com.xuzhenwei.agent.agent.ConversationManager;
import com.xuzhenwei.agent.technique.Technique;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * 技法025 — 6W3H
 *
 * <p>将模糊的创意"点子"转化为可执行、可评审的"策划案"。
 * 9 个维度：What, Why, Who, Whom, Where, When, How, How much, How many</p>
 */
@Component
public class SixWThreeHTechnique implements Technique {

    private final ChatClient chatClient;
    private final ConversationManager conversationManager;

    public SixWThreeHTechnique(ChatClient chatClient, ConversationManager conversationManager) {
        this.chatClient = chatClient;
        this.conversationManager = conversationManager;
    }

    @Override public String getId() { return "025"; }
    @Override public String getName() { return "6W3H"; }
    @Override public String getCategory() { return "打磨完善创意"; }
    @Override public String getDescription() { return "用9个核心维度将模糊的创意系统化为可执行的策划案"; }
    @Override public int getStepCount() { return 1; }

    @Override
    public Flux<AgentEvent> execute(String topic, String conversationId) {
        int step = 1;
        String stepName = "6W3H 策划案结构化";

        var prompt = """
                请将以下创意或课题，按照 6W3H 框架进行结构化展开：

                「%s」

                ## 6W3H 框架

                ### What（内容/目的）
                具体要做什么？最终目标是什么？

                ### Why（背景/理由/价值）
                为什么要做？解决了什么问题？带来什么价值？

                ### Who（对象/利害关系人）
                面向谁？谁会受到影响？

                ### Whom（合作伙伴/协同者）
                需要和谁合作？谁可以提供关键资源？

                ### Where（场所/范围）
                在什么范围内开展？地域、渠道、场景？

                ### When（时机/期间）
                什么时候开始？持续多久？关键里程碑？

                ### How（方法/手段）
                具体怎么做？用什么方法和技术？

                ### How much（成本/收益）
                需要多少预算？预期收益如何？

                ### How many（数量/规模）
                目标规模多大？用户数、覆盖范围？

                请用清晰、具体的语言填写每个维度，避免模糊表述。
                """.formatted(topic);

        return executeStep(step, stepName, prompt, conversationId);
    }

    private Flux<AgentEvent> executeStep(int step, String stepName, String prompt, String convId) {
        return Flux.create(sink -> {
            sink.next(AgentEvent.stepStart(step, stepName, getId()));

            try {
                String key = convId + "_" + getId();
                StringBuilder fullResponse = new StringBuilder();

                chatClient.prompt()
                        .user(prompt)
                        .stream()
                        .content()
                        .doOnComplete(() -> {
                            conversationManager.append(key, "AI", fullResponse.toString());
                            sink.next(AgentEvent.stepComplete(step, getId()));
                            sink.complete();
                        })
                        .doOnError(e -> { sink.next(AgentEvent.error("步骤异常: " + e.getMessage())); sink.complete(); })
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
