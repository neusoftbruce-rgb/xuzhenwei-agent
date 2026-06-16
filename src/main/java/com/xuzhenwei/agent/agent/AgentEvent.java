package com.xuzhenwei.agent.agent;

/**
 * Agent 事件 — SSE 流式输出的最小单元
 *
 * <p>前端通过 EventSource 接收此事件，逐步渲染 AI 思考过程。
 * 三种事件类型：STEP_START → STEP_CONTENT* → STEP_COMPLETE</p>
 */
public record AgentEvent(
        EventType type,
        int stepNumber,
        String stepName,
        String content,
        String techniqueId
) {

    public enum EventType {
        /** 步骤开始 */
        STEP_START,
        /** 步骤进行中（流式内容块） */
        STEP_CONTENT,
        /** 步骤完成 */
        STEP_COMPLETE,
        /** 全部技法执行完毕 */
        TECHNIQUE_COMPLETE,
        /** 错误事件 */
        ERROR
    }

    // ---- 工厂方法 ----

    public static AgentEvent stepStart(int step, String name, String techniqueId) {
        return new AgentEvent(EventType.STEP_START, step, name, null, techniqueId);
    }

    public static AgentEvent stepContent(int step, String content, String techniqueId) {
        return new AgentEvent(EventType.STEP_CONTENT, step, null, content, techniqueId);
    }

    public static AgentEvent stepComplete(int step, String techniqueId) {
        return new AgentEvent(EventType.STEP_COMPLETE, step, null, null, techniqueId);
    }

    public static AgentEvent techniqueComplete(String techniqueId) {
        return new AgentEvent(EventType.TECHNIQUE_COMPLETE, 0, null, null, techniqueId);
    }

    public static AgentEvent error(String message) {
        return new AgentEvent(EventType.ERROR, 0, null, message, null);
    }
}
