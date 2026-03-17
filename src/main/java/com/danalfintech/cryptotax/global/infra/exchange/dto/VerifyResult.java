package com.danalfintech.cryptotax.global.infra.exchange.dto;

public record VerifyResult(
        boolean valid,
        String message
) {}