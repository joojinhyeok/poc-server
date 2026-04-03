package com.danalfintech.cryptotax.exchange.common.dto;

public record ExchangeVerifyResponse(
        boolean isSuccess,
        String message
) {
    public static ExchangeVerifyResponse of(boolean isSuccess, String message) {
        return new ExchangeVerifyResponse(isSuccess, message);
    }
}
