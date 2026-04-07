package com.danalfintech.cryptotax.exchange.common.dto;

import jakarta.validation.constraints.NotBlank;

public record ExchangeKeyUpdateRequest(
        @NotBlank(message = "Access Key는 필수입니다.")
        String accessKey,

        @NotBlank(message = "Secret Key는 필수입니다.")
        String secretKey,

        String memo
) {}