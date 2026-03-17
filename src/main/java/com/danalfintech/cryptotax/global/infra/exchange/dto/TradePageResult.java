package com.danalfintech.cryptotax.global.infra.exchange.dto;

import java.util.List;

public record TradePageResult(
        List<TradeItem> trades,
        boolean hasMore,
        String nextCursor
) {}