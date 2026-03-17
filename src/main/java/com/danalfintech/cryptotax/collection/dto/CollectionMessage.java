package com.danalfintech.cryptotax.collection.dto;

import com.danalfintech.cryptotax.collection.domain.CollectionJobType;
import com.danalfintech.cryptotax.exchange.common.Exchange;

import java.time.LocalDateTime;

public record CollectionMessage(
        Long jobId,
        Long userId,
        Exchange exchange,
        Long apiKeyId,
        CollectionJobType type,
        LocalDateTime requestedAt
) {}