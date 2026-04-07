package com.danalfintech.cryptotax.exchange.bithumb.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record BithumbMarket(
        String market,
        @JsonProperty("korean_name") String koreanName,
        @JsonProperty("english_name") String englishName,
        @JsonProperty("market_warning") String marketWarning
) {}
