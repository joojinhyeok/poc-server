package com.danalfintech.cryptotax.global.infra.redis;

import com.danalfintech.cryptotax.global.common.RedisKeyBuilder;
import com.danalfintech.cryptotax.global.infra.exchange.ExchangeContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;

@Slf4j
public class FixedWindowPolicy implements ExchangeRateLimitPolicy {

    private static final String LUA_SCRIPT = """
            local key = KEYS[1]
            local limit = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local current = redis.call('INCR', key)
            if current == 1 then
                redis.call('EXPIRE', key, window)
            end
            return current
            """;

    private final RedisTemplate<String, String> redisTemplate;
    private final int limit;
    private final int windowSeconds;
    private final DefaultRedisScript<Long> script;

    public FixedWindowPolicy(RedisTemplate<String, String> redisTemplate, int limit, int windowSeconds) {
        this.redisTemplate = redisTemplate;
        this.limit = limit;
        this.windowSeconds = windowSeconds;
        this.script = new DefaultRedisScript<>(LUA_SCRIPT, Long.class);
    }

    @Override
    public boolean tryAcquire(ExchangeContext ctx, int weight) {
        try {
            long windowId = System.currentTimeMillis() / (windowSeconds * 1000L);
            String key = RedisKeyBuilder.rateLimitFixedWindow(ctx, windowId);
            Long current = redisTemplate.execute(
                    script,
                    Collections.singletonList(key),
                    String.valueOf(limit),
                    String.valueOf(windowSeconds)
            );
            return current != null && current <= limit;
        } catch (Exception e) {
            log.warn("FixedWindowPolicy Redis 실패, fail-open: exchange={}", ctx.exchange(), e);
            return true;
        }
    }
}
