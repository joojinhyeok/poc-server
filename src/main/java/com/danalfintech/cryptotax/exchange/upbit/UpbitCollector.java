package com.danalfintech.cryptotax.exchange.upbit;

import com.danalfintech.cryptotax.collection.domain.*;
import com.danalfintech.cryptotax.collection.service.CollectionProgressService;
import com.danalfintech.cryptotax.exchange.common.Exchange;
import com.danalfintech.cryptotax.exchange.common.ExchangeApiKey;
import com.danalfintech.cryptotax.global.infra.exchange.ExchangeCollector;
import com.danalfintech.cryptotax.global.infra.exchange.dto.*;
import com.danalfintech.cryptotax.portfolio.domain.Asset;
import com.danalfintech.cryptotax.portfolio.domain.AssetRepository;
import com.danalfintech.cryptotax.portfolio.domain.TradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class UpbitCollector implements ExchangeCollector {

    private static final int PAGE_LIMIT = 100;

    private final UpbitConnector connector;
    private final TradeRepository tradeRepository;
    private final AssetRepository assetRepository;
    private final SyncCursorRepository syncCursorRepository;
    private final CollectionJobRepository collectionJobRepository;
    private final CollectionProgressService progressService;

    @Override
    @Transactional
    public CollectionResult collectAll(CollectionJob job, ExchangeApiKey key) {
        Long userId = job.getUserId();
        int totalNewTrades = 0;
        int processedSymbols = 0;
        StringBuilder failReasons = new StringBuilder();

        try {
            // 마켓 목록 조회
            List<String> symbols = connector.getMarkets(key);
            int totalSymbols = symbols.size();
            job.setTotalSymbols(totalSymbols);
            collectionJobRepository.save(job);

            for (String symbol : symbols) {
                try {
                    int symbolTrades = collectSymbolTrades(userId, key, symbol, null);
                    totalNewTrades += symbolTrades;
                    processedSymbols++;

                    progressService.updateProgress(job.getId(), "PROCESSING",
                            totalSymbols, processedSymbols, totalNewTrades);
                } catch (Exception e) {
                    log.warn("심볼 수집 실패 (SKIP): symbol={}, error={}", symbol, e.getMessage());
                    failReasons.append(symbol).append(": ").append(e.getMessage()).append("; ");
                }
            }

            // 잔고 동기화
            syncBalances(userId, key);

            // 최종 상태 결정
            CollectionJobStatus finalStatus;
            if (processedSymbols == totalSymbols) {
                finalStatus = CollectionJobStatus.COMPLETED;
            } else if (processedSymbols > 0) {
                finalStatus = CollectionJobStatus.PARTIAL;
            } else {
                finalStatus = CollectionJobStatus.FAILED;
            }

            String failReason = failReasons.isEmpty() ? null : failReasons.toString();
            return new CollectionResult(finalStatus, totalSymbols, processedSymbols, totalNewTrades, failReason);

        } catch (Exception e) {
            log.error("전체 수집 실패: userId={}", userId, e);
            return new CollectionResult(CollectionJobStatus.FAILED, 0, processedSymbols, totalNewTrades, e.getMessage());
        }
    }

    @Override
    @Transactional
    public CollectionResult collectIncremental(CollectionJob job, ExchangeApiKey key) {
        Long userId = job.getUserId();
        int totalNewTrades = 0;
        int processedSymbols = 0;
        StringBuilder failReasons = new StringBuilder();

        try {
            List<String> symbols = connector.getMarkets(key);
            int totalSymbols = symbols.size();
            job.setTotalSymbols(totalSymbols);
            collectionJobRepository.save(job);

            for (String symbol : symbols) {
                try {
                    // SyncCursor에서 마지막 트레이드 ID 조회
                    SyncCursor cursor = syncCursorRepository
                            .findByUserIdAndExchangeAndSymbol(userId, Exchange.UPBIT, symbol)
                            .orElse(null);

                    String fromId = (cursor != null) ? cursor.getLastTradeId() : null;
                    int symbolTrades = collectSymbolTrades(userId, key, symbol, fromId);
                    totalNewTrades += symbolTrades;
                    processedSymbols++;

                    progressService.updateProgress(job.getId(), "PROCESSING",
                            totalSymbols, processedSymbols, totalNewTrades);
                } catch (Exception e) {
                    log.warn("증분 수집 실패 (SKIP): symbol={}, error={}", symbol, e.getMessage());
                    failReasons.append(symbol).append(": ").append(e.getMessage()).append("; ");
                }
            }

            // 잔고 동기화
            syncBalances(userId, key);

            CollectionJobStatus finalStatus;
            if (processedSymbols == totalSymbols) {
                finalStatus = CollectionJobStatus.COMPLETED;
            } else if (processedSymbols > 0) {
                finalStatus = CollectionJobStatus.PARTIAL;
            } else {
                finalStatus = CollectionJobStatus.FAILED;
            }

            String failReason = failReasons.isEmpty() ? null : failReasons.toString();
            return new CollectionResult(finalStatus, totalSymbols, processedSymbols, totalNewTrades, failReason);

        } catch (Exception e) {
            log.error("증분 수집 실패: userId={}", userId, e);
            return new CollectionResult(CollectionJobStatus.FAILED, 0, processedSymbols, totalNewTrades, e.getMessage());
        }
    }

    private int collectSymbolTrades(Long userId, ExchangeApiKey key, String symbol, String fromId) {
        int totalInserted = 0;
        String cursor = fromId;

        while (true) {
            TradePageResult page = connector.getTrades(key, symbol, cursor, PAGE_LIMIT);

            if (page.trades().isEmpty()) {
                break;
            }

            int pageInserted = 0;
            String lastTradeId = null;
            LocalDateTime lastTradedAt = null;

            for (TradeItem item : page.trades()) {
                int inserted = tradeRepository.insertIgnore(
                        userId,
                        Exchange.UPBIT.name(),
                        item.exchangeTradeId(),
                        item.symbol(),
                        item.side(),
                        item.price(),
                        item.quantity(),
                        item.fee(),
                        item.feeCurrency(),
                        item.tradedAt(),
                        item.market()
                );
                pageInserted += inserted;
                lastTradeId = item.exchangeTradeId();
                lastTradedAt = item.tradedAt();
            }

            totalInserted += pageInserted;

            // SyncCursor 업데이트
            if (lastTradeId != null) {
                updateSyncCursor(userId, symbol, lastTradeId, lastTradedAt);
            }

            // 꼬리 확인: 응답 건수 < limit이면 마지막 페이지
            if (!page.hasMore()) {
                break;
            }

            cursor = page.nextCursor();
        }

        return totalInserted;
    }

    private void updateSyncCursor(Long userId, String symbol, String lastTradeId, LocalDateTime lastSyncedAt) {
        SyncCursor cursor = syncCursorRepository
                .findByUserIdAndExchangeAndSymbol(userId, Exchange.UPBIT, symbol)
                .orElse(null);

        if (cursor != null) {
            cursor.update(lastTradeId, lastSyncedAt);
        } else {
            cursor = SyncCursor.builder()
                    .userId(userId)
                    .exchange(Exchange.UPBIT)
                    .symbol(symbol)
                    .lastTradeId(lastTradeId)
                    .lastSyncedAt(lastSyncedAt)
                    .build();
        }
        syncCursorRepository.save(cursor);
    }

    private void syncBalances(Long userId, ExchangeApiKey key) {
        try {
            List<BalanceItem> balances = connector.getBalances(key);
            for (BalanceItem item : balances) {
                BigDecimal totalAmount = item.balance().add(item.locked());
                if (totalAmount.compareTo(BigDecimal.ZERO) == 0) {
                    continue;
                }

                Asset asset = assetRepository
                        .findByUserIdAndExchangeAndCoin(userId, Exchange.UPBIT, item.coin())
                        .orElse(null);

                if (asset != null) {
                    asset.updateBalance(item.balance(), item.avgBuyPrice(), item.locked());
                } else {
                    asset = Asset.builder()
                            .userId(userId)
                            .exchange(Exchange.UPBIT)
                            .coin(item.coin())
                            .amount(item.balance())
                            .avgBuyPrice(item.avgBuyPrice())
                            .lockedAmount(item.locked())
                            .build();
                }
                assetRepository.save(asset);
            }
        } catch (Exception e) {
            log.warn("잔고 동기화 실패: userId={}", userId, e);
        }
    }
}