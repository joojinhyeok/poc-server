package com.danalfintech.cryptotax.collection.dto;

public record ProgressData(
        String status,
        int totalSymbols,
        int processedSymbols,
        int newTradesCount
) {}
