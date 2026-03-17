package com.danalfintech.cryptotax.exchange.upbit.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record UpbitTrade(
        String uuid,
        String side,
        @JsonProperty("ord_type") String ordType,
        String price,
        String state,
        String market,
        @JsonProperty("created_at") String createdAt,
        String volume,
        @JsonProperty("remaining_volume") String remainingVolume,
        @JsonProperty("executed_volume") String executedVolume,
        @JsonProperty("paid_fee") String paidFee,
        @JsonProperty("trades_count") int tradesCount
) {}