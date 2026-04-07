package com.danalfintech.cryptotax.global.infra.redis;

import com.danalfintech.cryptotax.exchange.common.entity.Exchange;
import com.danalfintech.cryptotax.global.config.ExchangeProperties;
import com.danalfintech.cryptotax.global.infra.exchange.ExchangeContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.EnumMap;
import java.util.Map;

@Slf4j
@Component
public class RateLimiterRegistry {

    private final RedisTemplate<String, String> redisTemplate;
    private final ExchangeProperties exchangeProperties;
    private final Map<Exchange, ExchangeRateLimitPolicy> policies = new EnumMap<>(Exchange.class);

    public RateLimiterRegistry(RedisTemplate<String, String> redisTemplate, ExchangeProperties exchangeProperties) {
        this.redisTemplate = redisTemplate;
        this.exchangeProperties = exchangeProperties;
    }

    @PostConstruct
    public void init() {
        exchangeProperties.getRateLimit().forEach((exchangeName, config) -> {
            try {
                Exchange exchange = Exchange.valueOf(exchangeName);
                ExchangeRateLimitPolicy policy = switch (config.getType()) {
                    case "WEIGHT_BUDGET" -> new WeightBudgetPolicy(redisTemplate, config.getLimit(), config.getWindowSeconds());
                    default -> new FixedWindowPolicy(redisTemplate, config.getLimit(), config.getWindowSeconds());
                };

                policies.put(exchange, policy);
                log.info("Rate limit 정책 등록: exchange={}, type={}, limit={}, window={}s",
                        exchange, config.getType(), config.getLimit(), config.getWindowSeconds());
            } catch (Exception e) {
                log.warn("Rate limit 설정 파싱 실패: exchange={}", exchangeName, e);
            }
        });
    }

    public boolean tryAcquire(ExchangeContext ctx, int weight) {
        ExchangeRateLimitPolicy policy = policies.get(ctx.exchange());
        if (policy == null) {
            log.warn("Rate limit 정책이 없는 거래소: {}", ctx.exchange());
            return true;
        }
        return policy.tryAcquire(ctx, weight);
    }
}
