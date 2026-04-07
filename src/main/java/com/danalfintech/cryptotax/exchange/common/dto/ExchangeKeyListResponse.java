package com.danalfintech.cryptotax.exchange.common.dto;

import java.util.List;

public record ExchangeKeyListResponse(
        List<ExchangeKeyResponse> keys
) {
    public static ExchangeKeyListResponse from(List<ExchangeKeyResponse> keys) {
        return new ExchangeKeyListResponse(keys);
    }
}