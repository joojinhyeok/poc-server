package com.danalfintech.cryptotax.collection.dto;

import com.danalfintech.cryptotax.collection.domain.CollectionJobType;
import com.danalfintech.cryptotax.exchange.common.Exchange;
import jakarta.validation.constraints.NotNull;

public record CollectionStartRequest(
        @NotNull Exchange exchange,
        @NotNull CollectionJobType type
) {}