package com.danalfintech.cryptotax.global.infra.exchange.dto;

import java.math.BigDecimal;

public record BalanceItem(
        String coin,
        BigDecimal balance,
        BigDecimal locked,
        BigDecimal avgBuyPrice
) {}