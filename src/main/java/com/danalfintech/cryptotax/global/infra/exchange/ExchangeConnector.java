package com.danalfintech.cryptotax.global.infra.exchange;

import com.danalfintech.cryptotax.exchange.common.ExchangeApiKey;
import com.danalfintech.cryptotax.global.infra.exchange.dto.BalanceItem;
import com.danalfintech.cryptotax.global.infra.exchange.dto.TradePageResult;
import com.danalfintech.cryptotax.global.infra.exchange.dto.VerifyResult;

import java.util.List;

public interface ExchangeConnector {

    List<BalanceItem> getBalances(ExchangeApiKey key);

    TradePageResult getTrades(ExchangeApiKey key, String symbol, String fromId, int limit);

    List<String> getMarkets(ExchangeApiKey key);

    VerifyResult verify(ExchangeApiKey key);
}