package com.danalfintech.cryptotax.global.infra.redis;

import com.danalfintech.cryptotax.exchange.common.Exchange;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.EnumMap;
import java.util.Map;

@Slf4j
@Component
public class RateLimiterRegistry {

    private final RedisTemplate<String, String> redisTemplate;
    private final Map<Exchange, ExchangeRateLimitPolicy> policies = new EnumMap<>(Exchange.class);

    public RateLimiterRegistry(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @PostConstruct
    public void init() {
        // 안전 마진 적용된 값 (거래소 공식 한도의 ~80%)
        policies.put(Exchange.UPBIT, new FixedWindowPolicy(redisTemplate, 8, 1));
        policies.put(Exchange.BINANCE, new WeightBudgetPolicy(redisTemplate, 5000, 60));
        policies.put(Exchange.BITHUMB, new FixedWindowPolicy(redisTemplate, 15, 1));
        policies.put(Exchange.BYBIT, new FixedWindowPolicy(redisTemplate, 80, 5));
        policies.put(Exchange.KORBIT, new FixedWindowPolicy(redisTemplate, 40, 1));
        policies.put(Exchange.COINONE, new FixedWindowPolicy(redisTemplate, 8, 1));
        policies.put(Exchange.GATEIO, new FixedWindowPolicy(redisTemplate, 8, 1));
        policies.put(Exchange.OKX, new FixedWindowPolicy(redisTemplate, 8, 1));
    }

    public ExchangeRateLimitPolicy getPolicy(Exchange exchange) {
        return policies.get(exchange);
    }

    public boolean tryAcquire(Exchange exchange, int weight) {
        ExchangeRateLimitPolicy policy = policies.get(exchange);
        if (policy == null) {
            log.warn("Rate limit 정책이 없는 거래소: {}", exchange);
            return true;
        }
        return policy.tryAcquire(exchange, weight);
    }
}