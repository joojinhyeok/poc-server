package com.danalfintech.cryptotax.exchange.bithumb.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record BithumbTrade(
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
