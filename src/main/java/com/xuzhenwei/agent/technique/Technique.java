package com.xuzhenwei.agent.technique;

import com.xuzhenwei.agent.agent.AgentEvent;
import reactor.core.publisher.Flux;

/**
 * 技法接口 — 56 种人机共想技法的统一契约
 *
 * <p>每条技法实现此接口，定义自己的多步 Prompt 工作流。
 * 技法是"徐振伟智能体"最核心的 IP，每条技法 = 一个独立的创意方法论。</p>
 *
 * <p>设计原则：
 * <ul>
 *   <li>每个技法返回 Flux<AgentEvent>，支持流式输出到前端</li>
 *   <li>技法之间通过 TechniqueRegistry 统一管理</li>
 *   <li>技法可独立测试、独立部署、独立迭代优化</li>
 * </ul>
 */
public interface Technique {

    /** 技法编号（3位数字，对应原书编号） */
    String getId();

    /** 技法名称（中文） */
    String getName();

    /** 所属分类：第1部/第2部/第3部/第4部 */
    String getCategory();

    /** 技法一句话描述 */
    String getDescription();

    /** 包含的步骤数 */
    int getStepCount();

    /**
     * 执行该技法
     *
     * @param topic           用户输入的课题
     * @param conversationId  会话 ID（用于关联上下文记忆）
     * @return 流式 AgentEvent，每步输出一个事件
     */
    Flux<AgentEvent> execute(String topic, String conversationId);

    /**
     * 关联技法 —— v3.0 方法8：技法关系图谱
     * 返回当前技法的后继技法（执行完本条后推荐的下一步）。
     * 默认空列表，子类可覆盖。
     */
    default List<String> getRelated() { return List.of(); }
}
