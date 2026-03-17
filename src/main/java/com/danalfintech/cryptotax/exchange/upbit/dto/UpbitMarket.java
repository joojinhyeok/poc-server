package com.danalfintech.cryptotax.exchange.upbit.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record UpbitMarket(
        String market,
        @JsonProperty("korean_name") String koreanName,
        @JsonProperty("english_name") String englishName,
        @JsonProperty("market_warning") String marketWarning
) {}