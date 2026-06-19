package com.xuzhenwei.agent.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 请求频率限制器 — 令牌桶算法
 *
 * <p>v2.3 新增。保护 DeepSeek API 不被恶意或意外刷爆。
 * 每IP每分钟最多20次普通请求，深度思考最多10次/分钟。</p>
 *
 * <p>算法：每个IP维护一个令牌桶，以恒定速率填充，请求时消耗令牌。
 * 桶容量=限流阈值，填充速率=阈值/分钟。</p>
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    /** 普通请求：20次/分钟/IP */
    private static final int DEFAULT_CAPACITY = 20;
    /** 深度思考：10次/分钟/IP */
    private static final int DEEP_THINK_CAPACITY = 10;
    /** 填充间隔（毫秒）：60秒/容量 = 每个令牌间隔 */
    private static final long WINDOW_MS = 60_000;

    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    /**
     * 尝试获取普通请求令牌
     * @param clientId 客户端标识（IP 或 sessionId）
     * @return true=允许, false=限流
     */
    public boolean tryAcquire(String clientId) {
        return tryAcquire(clientId, false);
    }

    /**
     * 尝试获取令牌
     * @param clientId 客户端标识
     * @param deepThink 是否为深度思考请求（容量更小）
     */
    public boolean tryAcquire(String clientId, boolean deepThink) {
        int capacity = deepThink ? DEEP_THINK_CAPACITY : DEFAULT_CAPACITY;
        TokenBucket bucket = buckets.computeIfAbsent(clientId, k -> new TokenBucket(capacity));
        return bucket.tryConsume(capacity);
    }

    /**
     * 获取当前剩余令牌数
     */
    public int getRemainingTokens(String clientId) {
        TokenBucket bucket = buckets.get(clientId);
        return bucket != null ? bucket.tokens : DEFAULT_CAPACITY;
    }

    /**
     * 获取重试等待秒数
     */
    public long getRetryAfterSeconds(String clientId, boolean deepThink) {
        TokenBucket bucket = buckets.get(clientId);
        if (bucket == null) return 1;
        int capacity = deepThink ? DEEP_THINK_CAPACITY : DEFAULT_CAPACITY;
        // 估算需要等待的时间：缺几个令牌 × 每个令牌的填充间隔
        int deficit = Math.max(0, 1 - bucket.tokens);
        long waitMs = deficit * (WINDOW_MS / capacity) + 1000; // 至少等1秒
        return Math.max(1, waitMs / 1000);
    }

    /**
     * 清理过期桶（由调度任务定期调用，防止内存泄漏）
     */
    public void evictStaleBuckets() {
        long now = System.currentTimeMillis();
        buckets.entrySet().removeIf(e -> {
            boolean stale = (now - e.getValue().lastRefill) > WINDOW_MS * 2;
            if (stale) log.debug("清理过期令牌桶: {}", e.getKey());
            return stale;
        });
    }

    // ---- 令牌桶内部类 ----

    private static class TokenBucket {
        int tokens;
        int capacity;
        long lastRefill;

        TokenBucket(int capacity) {
            this.capacity = capacity;
            this.tokens = capacity; // 初始满桶
            this.lastRefill = System.currentTimeMillis();
        }

        synchronized boolean tryConsume(int cap) {
            refill(cap);
            if (tokens > 0) {
                tokens--;
                return true;
            }
            return false;
        }

        private void refill(int cap) {
            long now = System.currentTimeMillis();
            long elapsed = now - lastRefill;
            // 按容量比例填充
            int newTokens = (int) (elapsed * cap / WINDOW_MS);
            if (newTokens > 0) {
                tokens = Math.min(cap, tokens + newTokens);
                lastRefill = now;
            }
        }
    }
}
