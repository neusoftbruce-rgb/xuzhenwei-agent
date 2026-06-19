package com.xuzhenwei.agent.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token 节省器 — 多种策略减少 API 调用消耗
 *
 * <p>策略：
 * <ol>
 *   <li>智能深浅判断：简单问题用快模型，复杂问题才用深度推理</li>
 *   <li>技法推荐缓存：相同/相似问题复用推荐结果</li>
 *   <li>历史裁剪：只保留最近 5 轮对话</li>
 *   <li>Prompt 压缩：去掉不必要的格式化指令</li>
 * </ol>
 */
@Component
public class TokenSaver {

    private static final Logger log = LoggerFactory.getLogger(TokenSaver.class);

    /** 推荐结果缓存：key=问题hash, value=推荐结果 */
    private final Map<String, CacheEntry> recommendCache = new ConcurrentHashMap<>();
    private static final int MAX_CACHE = 200;

    /**
     * 判断是否需要深度思考
     * 简单问题用快模型省钱，复杂问题才用 reasoner
     */
    public boolean shouldDeepThink(String question) {
        if (question == null || question.isBlank()) return false;

        // 简单问题关键词 → 快模型
        String[] simplePatterns = {
                "你好", "谢谢", "再见", "是什么", "怎么用",
                "介绍一下", "举例", "什么是", "帮我查",
                "翻译", "总结一下", "概括"
        };
        for (String p : simplePatterns) {
            if (question.contains(p) && question.length() < 30) {
                log.info("⚡ 简单问题，使用快模型");
                return false;
            }
        }

        // 复杂问题关键词 → 深度思考
        String[] complexPatterns = {
                "分析", "战略", "规划", "方案", "策略",
                "深度", "诊断", "评估", "预测", "优化",
                "怎么赚钱", "怎么办", "如何提升", "风险",
                "竞争", "转型", "创新", "改革", "设计"
        };
        for (String p : complexPatterns) {
            if (question.contains(p)) {
                log.info("🧠 复杂问题，使用深度推理");
                return true;
            }
        }

        // 问题超过 50 字 → 深度
        if (question.length() > 50) {
            log.info("🧠 长问题({}字)，使用深度推理", question.length());
            return true;
        }

        return false;
    }

    /**
     * 缓存推荐结果
     */
    public void cacheRecommend(String question, String result) {
        if (recommendCache.size() > MAX_CACHE) {
            // 随机淘汰一半
            var it = recommendCache.keySet().iterator();
            int toRemove = MAX_CACHE / 2;
            for (int i = 0; i < toRemove && it.hasNext(); i++) {
                it.next(); it.remove();
            }
        }
        String key = hash(question);
        recommendCache.put(key, new CacheEntry(result, System.currentTimeMillis()));
    }

    /**
     * 查找缓存的推荐结果
     */
    public String getCachedRecommend(String question) {
        String key = hash(question);
        CacheEntry entry = recommendCache.get(key);
        if (entry != null && (System.currentTimeMillis() - entry.time) < 3600_000) { // 1小时
            log.info("💾 命中推荐缓存");
            return entry.result;
        }
        return null;
    }

    /**
     * 压缩 Prompt —— 去掉不必要的格式化指令
     */
    public String compressPrompt(String prompt) {
        // 去掉重复的"请""要求""注意"等
        return prompt
                .replaceAll("(?m)^\\s*\\*\\*要求\\*\\*[：:].*$", "")
                .replaceAll("(?m)^\\s*\\*\\*注意\\*\\*[：:].*$", "")
                .replaceAll("(?m)^\\s*>[>\\s]*(\\*\\*要求\\*\\*|\\*\\*注意\\*\\*|\\*\\*请\\*\\*).*$", "")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
    }

    /**
     * 限制输出长度提示
     */
    public String getLengthLimitHint() {
        return "请简洁回答，控制在 500 字以内，用要点列表格式。";
    }

    private String hash(String text) {
        // FIX-05: Use SHA-256 to avoid hash collisions (String.hashCode() has 32-bit space)
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.trim().toLowerCase().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            // Take first 16 hex chars (64 bits) — sufficient for cache keys
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(text.trim().toLowerCase().hashCode());
        }
    }

    private record CacheEntry(String result, long time) {}
}
