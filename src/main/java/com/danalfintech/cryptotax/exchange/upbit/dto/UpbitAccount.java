package com.danalfintech.cryptotax.exchange.upbit.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record UpbitAccount(
        String currency,
        String balance,
        String locked,
        @JsonProperty("avg_buy_price") String avgBuyPrice,
        @JsonProperty("avg_buy_price_modified") boolean avgBuyPriceModified,
        @JsonProperty("unit_currency") String unitCurrency
) {}