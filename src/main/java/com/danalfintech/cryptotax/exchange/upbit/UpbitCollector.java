package com.danalfintech.cryptotax.exchange.upbit;

import com.danalfintech.cryptotax.collection.domain.SyncCursor;
import com.danalfintech.cryptotax.collection.domain.SyncCursorRepository;
import com.danalfintech.cryptotax.exchange.common.entity.Exchange;
import com.danalfintech.cryptotax.exchange.common.entity.ExchangeApiKey;
import com.danalfintech.cryptotax.global.infra.exchange.ExchangeCollector;
import com.danalfintech.cryptotax.global.infra.exchange.dto.BalanceItem;
import com.danalfintech.cryptotax.global.infra.exchange.dto.SymbolCollectionResult;
import com.danalfintech.cryptotax.global.infra.exchange.dto.TradeItem;
import com.danalfintech.cryptotax.global.infra.exchange.dto.TradePageResult;
import com.danalfintech.cryptotax.portfolio.domain.Asset;
import com.danalfintech.cryptotax.portfolio.domain.AssetRepository;
import com.danalfintech.cryptotax.portfolio.domain.TradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class UpbitCollector implements ExchangeCollector {

    private static final int PAGE_LIMIT = 100;
    private static final DateTimeFormatter CURSOR_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final UpbitConnector connector;
    private final TradeRepository tradeRepository;
    private final AssetRepository assetRepository;
    private final SyncCursorRepository syncCursorRepository;

    @Override
    public Exchange getExchange() {
        return Exchange.UPBIT;
    }

    @Override
    @Transactional
    public SymbolCollectionResult collectSymbol(Long userId, ExchangeApiKey key, String symbol, String fromCursor) {
        try {
            int newTrades = collectSymbolTrades(userId, key, symbol, fromCursor);
            return SymbolCollectionResult.success(symbol, newTrades);
        } catch (Exception e) {
            log.error("심볼 수집 실패: userId={}, symbol={}", userId, symbol, e);
            return SymbolCollectionResult.failure(symbol, e.getMessage());
        }
    }

    @Override
    @Transactional
    public void syncBalances(Long userId, ExchangeApiKey key) {
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

    /**
     * 심볼별 체결 내역 페이지네이션 수집.
     * fromId는 ISO 8601 timestamp (start_time 파라미터) 또는 null (전체 수집).
     */
    private int collectSymbolTrades(Long userId, ExchangeApiKey key, String symbol, String fromId) {
        int totalInserted = 0;
        String cursor = fromId;

        while (true) {
            TradePageResult page = connector.getTrades(key, symbol, cursor, PAGE_LIMIT);

            if (page.trades().isEmpty()) {
                break;
            }

            int pageInserted = 0;
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
                lastTradedAt = item.tradedAt();
            }

            totalInserted += pageInserted;

            if (lastTradedAt != null) {
                String cursorValue = page.nextCursor() != null
                        ? page.nextCursor()
                        : lastTradedAt.format(CURSOR_FORMAT);
                updateSyncCursor(userId, symbol, cursorValue, lastTradedAt);
            }

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
}
