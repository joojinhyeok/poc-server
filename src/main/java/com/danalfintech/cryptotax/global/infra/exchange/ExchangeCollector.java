package com.danalfintech.cryptotax.global.infra.exchange;

import com.danalfintech.cryptotax.collection.domain.CollectionJob;
import com.danalfintech.cryptotax.exchange.common.ExchangeApiKey;
import com.danalfintech.cryptotax.global.infra.exchange.dto.CollectionResult;

public interface ExchangeCollector {

    CollectionResult collectAll(CollectionJob job, ExchangeApiKey key);

    CollectionResult collectIncremental(CollectionJob job, ExchangeApiKey key);
}