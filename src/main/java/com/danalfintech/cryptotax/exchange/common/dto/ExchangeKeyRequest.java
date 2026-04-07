package com.danalfintech.cryptotax.exchange.common.dto;

import com.danalfintech.cryptotax.exchange.common.entity.Exchange;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ExchangeKeyRequest(
        @NotNull(message = "거래소는 필수입니다.")
        Exchange exchange,

        @NotBlank(message = "Access Key는 필수입니다.")
        String accessKey,

        @NotBlank(message = "Secret Key는 필수입니다.")
        String secretKey,

        String memo
) {
}