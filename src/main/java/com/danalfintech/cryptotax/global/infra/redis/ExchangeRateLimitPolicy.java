package com.danalfintech.cryptotax.global.infra.redis;

import com.danalfintech.cryptotax.exchange.common.Exchange;

public interface ExchangeRateLimitPolicy {

    boolean tryAcquire(Exchange exchange, int weight);
}