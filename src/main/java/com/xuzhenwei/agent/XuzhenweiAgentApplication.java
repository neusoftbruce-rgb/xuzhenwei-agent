package com.xuzhenwei.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 徐振伟智能体 — 基于 AI 的人机共想创意协作系统
 *
 * <p>核心能力：
 * <ul>
 *   <li>56 种结构化创意技法，覆盖从"产生灵感"到"落地执行"全流程</li>
 *   <li>多轮深度对话，"3-4 轮人机拉锯"产生高质量创意</li>
 *   <li>虚拟专家团模拟，8 种角色多维度审视课题</li>
 *   <li>创意验证引擎，5 维度量化评分</li>
 *   <li>一键生成 6W3H 策划书 / 精益画布 / 财务预测 / 视频脚本</li>
 * </ul>
 *
 * <p>v2.2: 新增会话记忆系统 — 多会话持久化（H2文件/PostgreSQL）+
 * Memos云端同步 + 继续对话/改进模式</p>
 *
 * @author Xu Zhenwei
 * @since 2026-06-16
 */
@SpringBootApplication
@EnableAsync
public class XuzhenweiAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(XuzhenweiAgentApplication.class, args);
    }
}
