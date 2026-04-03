package com.danalfintech.cryptotax.collection.dto;

import com.danalfintech.cryptotax.collection.domain.CollectionJobType;
import com.danalfintech.cryptotax.exchange.common.entity.Exchange;

public record SymbolCollectionMessage(
        Long jobId,
        Long userId,
        Exchange exchange,
        Long apiKeyId,
        CollectionJobType type,
        String symbol,
        int totalSymbols
) {}
