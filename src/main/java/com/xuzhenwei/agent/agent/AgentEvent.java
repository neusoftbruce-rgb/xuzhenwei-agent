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
        ERROR,
        /** v3.4: 预处理开始 */
        PREPROCESS_START,
        /** v3.4: 预处理Phase1完成(本地) */
        PREPROCESS_PHASE1,
        /** v3.4: 预处理Phase2完成(智谱) */
        PREPROCESS_PHASE2,
        /** v3.4: 预处理结果JSON */
        PREPROCESS_RESULT
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

    // ---- v3.4 预处理事件工厂 ----

    public static AgentEvent preprocessStart() {
        return new AgentEvent(EventType.PREPROCESS_START, 0, "🔍 文本预处理中...", null, "preprocess");
    }

    public static AgentEvent preprocessPhase1(int keywordCount, int typoCount) {
        String msg = "✅ 提取关键词 " + keywordCount + "个";
        if (typoCount > 0) msg += " · 疑似错别字 " + typoCount + "个";
        return new AgentEvent(EventType.PREPROCESS_PHASE1, 0, msg, null, "preprocess");
    }

    public static AgentEvent preprocessPhase2(String intent, String domains) {
        return new AgentEvent(EventType.PREPROCESS_PHASE2, 0,
            "✅ 智谱精炼 · " + intent + " · " + domains, null, "preprocess");
    }

    /** content 字段携带 PreprocessResult 的 JSON 序列化 */
    public static AgentEvent preprocessResult(String json) {
        return new AgentEvent(EventType.PREPROCESS_RESULT, 0, null, json, "preprocess");
    }
}
