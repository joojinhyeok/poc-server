package com.danalfintech.cryptotax.global.infra.redis;

import com.danalfintech.cryptotax.global.infra.exchange.ExchangeContext;

public interface ExchangeRateLimitPolicy {

    boolean tryAcquire(ExchangeContext ctx, int weight);
}
