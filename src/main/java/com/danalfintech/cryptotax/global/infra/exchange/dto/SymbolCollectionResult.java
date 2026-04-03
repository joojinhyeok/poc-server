package com.danalfintech.cryptotax.global.infra.exchange.dto;

public record SymbolCollectionResult(
        boolean success,
        String symbol,
        int newTradesCount,
        String failReason
) {
    public static SymbolCollectionResult success(String symbol, int newTradesCount) {
        return new SymbolCollectionResult(true, symbol, newTradesCount, null);
    }

    public static SymbolCollectionResult failure(String symbol, String reason) {
        return new SymbolCollectionResult(false, symbol, 0, reason);
    }
}
