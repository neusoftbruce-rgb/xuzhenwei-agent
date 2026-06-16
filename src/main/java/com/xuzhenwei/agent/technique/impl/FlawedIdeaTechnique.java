package com.xuzhenwei.agent.technique.impl;

import com.xuzhenwei.agent.agent.AgentEvent;
import com.xuzhenwei.agent.agent.ConversationManager;
import com.xuzhenwei.agent.technique.Technique;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * 技法003 — 瑕疵创意（隙のあるアイデア）
 *
 * <p>故意让 AI 生成不成熟、有缺陷的创意草案。
 * "完美方案扼杀讨论，有缝隙的方案激发共创"。</p>
 *
 * <p>MVP 阶段的默认技法。</p>
 */
@Component
public class FlawedIdeaTechnique implements Technique {

    private final ChatClient chatClient;
    private final ConversationManager conversationManager;

    public FlawedIdeaTechnique(ChatClient chatClient, ConversationManager conversationManager) {
        this.chatClient = chatClient;
        this.conversationManager = conversationManager;
    }

    @Override public String getId() { return "003"; }
    @Override public String getName() { return "瑕疵创意"; }
    @Override public String getCategory() { return "第1部·立刻获得灵感"; }
    @Override public String getDescription() { return "故意生成不完美的创意草案，激发你的补充欲望和二次创意"; }
    @Override public int getStepCount() { return 2; }

    @Override
    public Flux<AgentEvent> execute(String topic, String conversationId) {
        return Flux.concat(
                step1_GenerateFlawedIdeas(topic, conversationId),
                step2_InspireExtension(topic, conversationId)
        );
    }

    private Flux<AgentEvent> step1_GenerateFlawedIdeas(String topic, String conversationId) {
        int step = 1;
        String stepName = "生成有缝隙的创意草案";

        var prompt = """
                关于「%s」，请提供 5 个"有缝隙"的创意草案。

                "有缝隙"是什么意思？
                - 核心概念清晰、有冲击力
                - 但执行细节刻意留白
                - 让看到的人忍不住想补充、延展、改良
                - 像一道好吃的半成品菜——闻着就饿了，但需要你亲手完成最后一步

                每个草案用 2-3 句话，不要写得太完整。
                """.formatted(topic);

        return executeStep(step, stepName, prompt, conversationId);
    }

    private Flux<AgentEvent> step2_InspireExtension(String topic, String conversationId) {
        int step = 2;
        String stepName = "激发延展思考";

        var prompt = """
                基于以上 5 个有缝隙的草案，请向用户提出 3 个"启发式问题"。

                这些问题应该引导用户思考：
                - 哪个草案的"缝隙"最吸引你？
                - 如果是你，你会如何填补这个缝隙？
                - 把两个草案的缝隙交叉缝合，会产生什么新东西？

                语气要像一位好的创意教练——鼓励但不 push。
                """;

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
