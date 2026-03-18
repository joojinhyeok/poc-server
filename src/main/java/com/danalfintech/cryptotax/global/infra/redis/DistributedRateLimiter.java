package com.danalfintech.cryptotax.global.infra.redis;

import com.danalfintech.cryptotax.global.infra.exchange.ExchangeContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Slf4j
@Component
@RequiredArgsConstructor
public class DistributedRateLimiter {

    private static final Duration FALLBACK_DELAY = Duration.ofSeconds(1);
    private static final int MAX_WAIT_ATTEMPTS = 50;

    private final RateLimiterRegistry registry;

    public void waitForPermit(ExchangeContext ctx, int weight) {
        for (int attempt = 0; attempt < MAX_WAIT_ATTEMPTS; attempt++) {
            if (registry.tryAcquire(ctx, weight)) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.warn("Rate limit 대기 초과, 보수적 딜레이 적용: exchange={}", ctx.exchange());
        try {
            Thread.sleep(FALLBACK_DELAY.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
