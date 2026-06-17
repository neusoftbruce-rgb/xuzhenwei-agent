package com.xuzhenwei.agent.technique.impl;

import com.xuzhenwei.agent.agent.AgentEvent;
import com.xuzhenwei.agent.agent.ConversationManager;
import com.xuzhenwei.agent.technique.Technique;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * 技法005 — 虚拟专家团
 *
 * <p>模拟 8 位不同领域的专家，从各自视角审视课题。
 * 用极低成本实现"人才多样性"。</p>
 */
@Component
public class ExpertPanelTechnique implements Technique {

    private final ChatClient chatClient;
    private final ConversationManager conversationManager;

    public ExpertPanelTechnique(ChatClient chatClient, ConversationManager conversationManager) {
        this.chatClient = chatClient;
        this.conversationManager = conversationManager;
    }

    private static final String EXPERT_ROLES = """
            请依次扮演以下 8 位专家，每位给出 1-2 条核心建议：

            1. **创意专家**：擅长跨界联想，总能找到风马牛不相及的事物之间的联系
            2. **技术专家**：关注技术可行性和实现路径，务实但不保守
            3. **商业专家**：聚焦商业模式、盈利能力和市场规模
            4. **颠覆者（Disruptor）**：质疑一切常规，提出打破行业规则的方案
            5. **社会学家**：从人的行为模式、社会趋势、文化变迁角度分析
            6. **幽默家**：用反直觉、荒诞的视角，在笑话中发现真理
            7. **冒险家**：敢想敢干，提出高风险高回报的大胆方案
            8. **用户代言人**：始终站在终端用户的立场，关注真实体验

            每位专家请标注角色名，用 2-4 句话陈述观点。
            观点之间要有差异化，不要重复。
            """;

    @Override public String getId() { return "005"; }
    @Override public String getName() { return "虚拟专家团"; }
    @Override public String getCategory() { return "快速产生灵感"; }
    @Override public String getDescription() { return "8位虚拟专家从不同视角审视课题，模拟高成本的多维度头脑风暴"; }
    @Override public int getStepCount() { return 2; }

    @Override
    public Flux<AgentEvent> execute(String topic, String conversationId) {
        return Flux.concat(
                step1_ExpertConsultation(topic, conversationId),
                step2_Synthesize(topic, conversationId)
        );
    }

    private Flux<AgentEvent> step1_ExpertConsultation(String topic, String conversationId) {
        int step = 1;
        String stepName = "专家团咨询（8位专家）";

        var prompt = """
                课题：「%s」

                %s

                请开始。
                """.formatted(topic, EXPERT_ROLES);

        return executeStep(step, stepName, prompt, conversationId);
    }

    private Flux<AgentEvent> step2_Synthesize(String topic, String conversationId) {
        int step = 2;
        String stepName = "综合提炼行动方向";

        var prompt = """
                基于以上 8 位专家的意见，请为课题「%s」提炼 3 个最有价值的行动方向。

                每个方向请说明：
                - 综合了哪几位专家的视角
                - 核心策略是什么
                - 第一步该做什么
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
