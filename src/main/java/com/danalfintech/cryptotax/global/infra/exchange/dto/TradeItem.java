package com.danalfintech.cryptotax.global.infra.exchange.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TradeItem(
        String exchangeTradeId,
        String symbol,
        String side,
        BigDecimal price,
        BigDecimal quantity,
        BigDecimal fee,
        String feeCurrency,
        LocalDateTime tradedAt,
        String market
) {}