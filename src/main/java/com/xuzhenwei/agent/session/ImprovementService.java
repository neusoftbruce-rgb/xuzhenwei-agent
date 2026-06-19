package com.xuzhenwei.agent.session;

import com.xuzhenwei.agent.project.entity.MessageRecord;
import com.xuzhenwei.agent.session.entity.Session;
import com.xuzhenwei.agent.technique.TechniqueRecommender;
import com.xuzhenwei.agent.technique.TechniqueRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 改进服务 — 管理会话的继续对话与改进流程
 *
 * <p>当用户对历史会话结果不满意，可以打开旧会话进行改进：
 * <ul>
 *   <li>agent_workflow — 自动分析会话技法链，推荐最佳改进技法</li>
 *   <li>manual_instruction — 用户手动输入改进指令</li>
 * </ul>
 * </p>
 */
@Service
public class ImprovementService {

    private static final Logger log = LoggerFactory.getLogger(ImprovementService.class);

    private final SessionService sessionService;
    private final TechniqueRecommender techniqueRecommender;
    private final TechniqueRegistry techniqueRegistry;

    public ImprovementService(SessionService sessionService,
                              TechniqueRecommender techniqueRecommender,
                              TechniqueRegistry techniqueRegistry) {
        this.sessionService = sessionService;
        this.techniqueRecommender = techniqueRecommender;
        this.techniqueRegistry = techniqueRegistry;
    }

    /**
     * 构建改进模式的系统提示词
     *
     * @param sessionId         会话ID
     * @param manualInstruction 手动改进指令（null 表示自动工作流模式）
     * @return 改进提示词
     */
    public String buildImprovementPrompt(String sessionId, String manualInstruction) {
        var messages = sessionService.getMessagesAsc(sessionId);
        var session = sessionService.getSession(sessionId).orElse(null);

        var sb = new StringBuilder();
        sb.append("## 改进任务\n\n");
        sb.append("你正在对之前的一次分析结果进行改进。请基于历史对话上下文，");
        sb.append("审视之前的分析过程和结论，找出可以改进的地方。\n\n");

        if (session != null && session.getSummary() != null) {
            sb.append("### 会话概要\n");
            sb.append(session.getSummary()).append("\n\n");
        }

        sb.append("### 历史对话（最近20轮）\n");
        int shown = 0;
        // 从最新开始，最多取20条
        int start = Math.max(0, messages.size() - 20);
        for (int i = start; i < messages.size(); i++) {
            var msg = messages.get(i);
            if (msg.getRole() == MessageRecord.MessageRole.SYSTEM) continue;
            String roleLabel = msg.getRole() == MessageRecord.MessageRole.USER ? "用户" : "徐振伟";
            String content = msg.getContent();
            if (content != null && content.length() > 500) {
                content = content.substring(0, 500) + "...";
            }
            sb.append("**").append(roleLabel).append("**: ").append(content).append("\n\n");
            shown++;
            if (shown >= 20) break;
        }

        if (manualInstruction != null && !manualInstruction.isBlank()) {
            sb.append("### 改进方向\n");
            sb.append("用户指定了以下改进方向，请据此进行改进：\n\n");
            sb.append(manualInstruction).append("\n\n");
        } else {
            sb.append("### 改进要求\n");
            sb.append("请批判性地审视之前的分析，找出不足之处，给出改进版的分析结果。\n");
            sb.append("特别关注：逻辑漏洞、遗漏的角度、不够具体的建议。\n\n");
        }

        sb.append("请直接输出改进后的完整结果，无需重复历史对话。");
        return sb.toString();
    }

    /**
     * 根据会话的技法链，推荐最佳改进技法
     *
     * @param sessionId 会话ID
     * @return 推荐的技法ID，默认返回 "030"（验证技法）
     */
    public String recommendImprovementTechnique(String sessionId) {
        var session = sessionService.getSession(sessionId).orElse(null);
        if (session == null) return "030";

        String chain = session.getTechniqueChain();
        if (chain == null || chain.isBlank()) {
            return "030"; // 默认：验证挑剔技法
        }

        // 根据已使用的技法，推荐互补的改进技法
        // 例如：用了 040 SWOT → 推荐 030 验证挑剔
        //      用了品牌相关 → 推荐 031 逆向思维
        //      用了创意生成 → 推荐 044 烦恼分析

        if (chain.contains("040") || chain.contains("SWOT")) {
            return "030"; // 验证挑剔：对 SWOT 分析进行验证
        }
        if (chain.contains("028") || chain.contains("036") || chain.contains("023")) {
            return "031"; // 逆向思维：对品牌策略进行反直觉思考
        }
        if (chain.contains("007") || chain.contains("008") || chain.contains("024")) {
            return "044"; // 烦恼分析：对创意进行用户视角审视
        }
        if (chain.contains("034") || chain.contains("050")) {
            return "027"; // 假说验证：对定价策略进行假设检验
        }

        // 获取消息内容进一步分析
        var messages = sessionService.getMessagesAsc(sessionId);
        String allText = messages.stream()
                .map(MessageRecord::getContent)
                .filter(c -> c != null)
                .collect(Collectors.joining(" "));

        // 简单关键词匹配
        if (allText.contains("品牌") || allText.contains("定位")) return "031";
        if (allText.contains("定价") || allText.contains("成本")) return "027";
        if (allText.contains("营销") || allText.contains("传播")) return "029";
        if (allText.contains("战略") || allText.contains("规划")) return "030";
        if (allText.contains("技术") || allText.contains("产品")) return "013";

        return "030"; // 默认
    }
}
