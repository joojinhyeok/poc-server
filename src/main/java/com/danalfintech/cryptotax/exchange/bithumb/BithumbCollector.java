package com.danalfintech.cryptotax.exchange.bithumb;

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
public class BithumbCollector implements ExchangeCollector {

    private static final int PAGE_LIMIT = 100;
    static final DateTimeFormatter CURSOR_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final BithumbConnector connector;
    private final TradeRepository tradeRepository;
    private final AssetRepository assetRepository;
    private final SyncCursorRepository syncCursorRepository;

    @Override
    public Exchange getExchange() {
        return Exchange.BITHUMB;
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
                        .findByUserIdAndExchangeAndCoin(userId, Exchange.BITHUMB, item.coin())
                        .orElse(null);

                if (asset != null) {
                    asset.updateBalance(item.balance(), item.avgBuyPrice(), item.locked());
                } else {
                    asset = Asset.builder()
                            .userId(userId)
                            .exchange(Exchange.BITHUMB)
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
     * 심볼별 체결 내역 수집 (desc 정렬 + 조기 중단).
     *
     * 빗썸 /v1/orders는 start_time을 지원하지 않으므로 order_by=desc(최신순)로 조회.
     * - 전체 수집 (fromCursor=null): 모든 페이지 순회
     * - 증분 수집 (fromCursor=timestamp): 이미 수집한 거래를 만나면 즉시 중단
     *
     * 커서 갱신은 전체 수집 완료 후 한 번만 수행.
     * desc 순회 중 페이지 단위로 커서를 갱신하면, 중간 크래시 시
     * 커서가 최신값으로 설정되어 이후 페이지의 거래를 영구 누락할 수 있기 때문.
     * insertIgnore가 재수집 시 중복을 방지하므로 안전함.
     */
    private int collectSymbolTrades(Long userId, ExchangeApiKey key, String symbol, String fromCursor) {
        LocalDateTime cutoff = (fromCursor != null && !fromCursor.isEmpty())
                ? LocalDateTime.parse(fromCursor, CURSOR_FORMAT)
                : null;

        int totalInserted = 0;
        String pageCursor = null;
        LocalDateTime newestTradedAt = null;

        outer:
        while (true) {
            TradePageResult page = connector.getTrades(key, symbol, pageCursor, PAGE_LIMIT);

            if (page.trades().isEmpty()) {
                break;
            }

            for (TradeItem item : page.trades()) {
                // desc 정렬: 최신→과거 순. cutoff 이전 거래를 만나면 이후는 전부 수집 완료 → 종료
                if (cutoff != null && !item.tradedAt().isAfter(cutoff)) {
                    break outer;
                }

                int inserted = tradeRepository.insertIgnore(
                        userId,
                        Exchange.BITHUMB.name(),
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
                totalInserted += inserted;

                // desc 정렬이므로 첫 번째 거래가 가장 최신
                if (newestTradedAt == null) {
                    newestTradedAt = item.tradedAt();
                }
            }

            if (!page.hasMore()) {
                break;
            }

            pageCursor = page.nextCursor();
        }

        // 수집 완료 후 커서를 가장 최신 거래 시각으로 갱신
        if (newestTradedAt != null) {
            updateSyncCursor(userId, symbol, newestTradedAt.format(CURSOR_FORMAT), newestTradedAt);
        }

        return totalInserted;
    }

    private void updateSyncCursor(Long userId, String symbol, String lastTradeId, LocalDateTime lastSyncedAt) {
        SyncCursor cursor = syncCursorRepository
                .findByUserIdAndExchangeAndSymbol(userId, Exchange.BITHUMB, symbol)
                .orElse(null);

        if (cursor != null) {
            cursor.update(lastTradeId, lastSyncedAt);
        } else {
            cursor = SyncCursor.builder()
                    .userId(userId)
                    .exchange(Exchange.BITHUMB)
                    .symbol(symbol)
                    .lastTradeId(lastTradeId)
                    .lastSyncedAt(lastSyncedAt)
                    .build();
        }
        syncCursorRepository.save(cursor);
    }
}
