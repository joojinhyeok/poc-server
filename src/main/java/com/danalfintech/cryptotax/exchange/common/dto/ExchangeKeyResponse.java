package com.danalfintech.cryptotax.exchange.common.dto;

import com.danalfintech.cryptotax.exchange.common.entity.Exchange;
import com.danalfintech.cryptotax.exchange.common.entity.ExchangeApiKey;

// 응답 DTO (보안상 SecretKey는 빼거나 마스킹 처리)
public record ExchangeKeyResponse(
        Long id,
        Exchange exchange,
        String accessKey,
        boolean isValid,
        String memo
) {
    public static ExchangeKeyResponse from(ExchangeApiKey entity) {
        return new ExchangeKeyResponse(
                entity.getId(),
                entity.getExchange(),
                entity.getAccessKey().substring(0, 6) + "****", // 앞부분만 보여주기 (보안)
                entity.isValid(),
                entity.getMemo()
        );
    }
}