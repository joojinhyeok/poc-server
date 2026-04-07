package com.danalfintech.cryptotax.exchange.bithumb.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record BithumbAccount(
        String currency,
        String balance,
        String locked,
        @JsonProperty("avg_buy_price") String avgBuyPrice,
        @JsonProperty("avg_buy_price_modified") boolean avgBuyPriceModified,
        @JsonProperty("unit_currency") String unitCurrency
) {}
