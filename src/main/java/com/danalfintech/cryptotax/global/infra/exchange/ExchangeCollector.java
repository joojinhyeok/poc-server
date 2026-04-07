package com.danalfintech.cryptotax.global.infra.exchange;

import com.danalfintech.cryptotax.exchange.common.entity.Exchange;
import com.danalfintech.cryptotax.exchange.common.entity.ExchangeApiKey;
import com.danalfintech.cryptotax.global.infra.exchange.dto.SymbolCollectionResult;

public interface ExchangeCollector {

    Exchange getExchange();

    /** 심볼 단위 수집 (Facade 패턴용) */
    SymbolCollectionResult collectSymbol(Long userId, ExchangeApiKey key, String symbol, String fromCursor);

    /** 전체 심볼 수집 완료 후 거래소 API에서 잔고를 조회하여 Asset 테이블에 동기화 */
    void syncBalances(Long userId, ExchangeApiKey key);
}
