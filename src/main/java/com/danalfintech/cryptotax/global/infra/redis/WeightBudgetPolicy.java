package com.danalfintech.cryptotax.global.infra.redis;

import com.danalfintech.cryptotax.global.common.RedisKeyBuilder;
import com.danalfintech.cryptotax.global.infra.exchange.ExchangeContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;

@Slf4j
public class WeightBudgetPolicy implements ExchangeRateLimitPolicy {

    private static final String LUA_SCRIPT = """
            local key = KEYS[1]
            local budget = tonumber(ARGV[1])
            local weight = tonumber(ARGV[2])
            local window = tonumber(ARGV[3])
            local current = redis.call('GET', key)
            if current == false then
                current = 0
            else
                current = tonumber(current)
            end
            if current + weight > budget then
                return 0
            end
            local newVal = redis.call('INCRBY', key, weight)
            if newVal == weight then
                redis.call('EXPIRE', key, window)
            end
            return 1
            """;

    private final RedisTemplate<String, String> redisTemplate;
    private final int budget;
    private final int windowSeconds;
    private final DefaultRedisScript<Long> script;

    public WeightBudgetPolicy(RedisTemplate<String, String> redisTemplate, int budget, int windowSeconds) {
        this.redisTemplate = redisTemplate;
        this.budget = budget;
        this.windowSeconds = windowSeconds;
        this.script = new DefaultRedisScript<>(LUA_SCRIPT, Long.class);
    }

    @Override
    public boolean tryAcquire(ExchangeContext ctx, int weight) {
        try {
            long windowId = System.currentTimeMillis() / (windowSeconds * 1000L);
            String key = RedisKeyBuilder.rateLimitWeight(ctx, windowId);
            Long result = redisTemplate.execute(
                    script,
                    Collections.singletonList(key),
                    String.valueOf(budget),
                    String.valueOf(weight),
                    String.valueOf(windowSeconds)
            );
            return result != null && result == 1L;
        } catch (Exception e) {
            log.warn("WeightBudgetPolicy Redis 실패, fail-open: exchange={}", ctx.exchange(), e);
            return true;
        }
    }
}
