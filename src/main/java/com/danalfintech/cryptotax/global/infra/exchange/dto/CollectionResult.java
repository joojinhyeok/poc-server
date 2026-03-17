package com.danalfintech.cryptotax.global.infra.exchange.dto;

import com.danalfintech.cryptotax.collection.domain.CollectionJobStatus;

public record CollectionResult(
        CollectionJobStatus finalStatus,
        int totalSymbols,
        int processedSymbols,
        int newTradesCount,
        String failReason
) {}