package com.danalfintech.cryptotax.exchange.upbit;

import com.danalfintech.cryptotax.collection.domain.SyncCursor;
import com.danalfintech.cryptotax.collection.domain.SyncCursorRepository;
import com.danalfintech.cryptotax.exchange.common.entity.Exchange;
import com.danalfintech.cryptotax.exchange.common.entity.ExchangeApiKey;
import com.danalfintech.cryptotax.exchange.common.repository.ExchangeApiKeyRepository;
import com.danalfintech.cryptotax.global.infra.exchange.dto.*;
import com.danalfintech.cryptotax.portfolio.domain.Asset;
import com.danalfintech.cryptotax.portfolio.domain.AssetRepository;
import com.danalfintech.cryptotax.portfolio.domain.Trade;
import com.danalfintech.cryptotax.portfolio.domain.TradeRepository;
import com.danalfintech.cryptotax.support.BaseIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;

@DisplayName("UpbitCollector 통합 테스트")
@Import(UpbitCollectorIntegrationTest.MockConnectorConfig.class)
class UpbitCollectorIntegrationTest extends BaseIntegrationTest {

    @TestConfiguration
    static class MockConnectorConfig {
        @Bean("upbitConnector")
        UpbitConnector upbitConnector() {
            UpbitConnector mock = mock(UpbitConnector.class);
            given(mock.getExchange()).willReturn(Exchange.UPBIT);
            return mock;
        }
    }

    @Autowired
    private UpbitConnector connector;

    @Autowired
    private UpbitCollector collector;

    @Autowired
    private TradeRepository tradeRepository;

    @Autowired
    private AssetRepository assetRepository;

    @Autowired
    private SyncCursorRepository syncCursorRepository;

    @Autowired
    private ExchangeApiKeyRepository apiKeyRepository;

    private ExchangeApiKey apiKey;
    private static final Long USER_ID = 100L;

    @BeforeEach
    void setUp() {
        reset(connector);
        given(connector.getExchange()).willReturn(Exchange.UPBIT);

        syncCursorRepository.deleteAllInBatch();
        tradeRepository.deleteAllInBatch();
        assetRepository.deleteAllInBatch();
        apiKeyRepository.deleteAllInBatch();

        apiKey = apiKeyRepository.saveAndFlush(
                ExchangeApiKey.builder()
                        .userId(USER_ID)
                        .exchange(Exchange.UPBIT)
                        .accessKey("test-access")
                        .secretKey("test-secret-key-must-be-at-least-256-bits")
                        .memo("test")
                        .build()
        );
    }

    @Nested
    @DisplayName("collectSymbol — 심볼별 거래 수집 → DB 저장 검증")
    class CollectSymbol {

        @Test
        @DisplayName("단일 페이지 수집 시 거래 내역이 DB에 저장된다")
        void savesTradesInDatabase() {
            TradeItem trade = new TradeItem("uuid-1", "BTC", "BUY",
                    new BigDecimal("50000000"), new BigDecimal("1.0"),
                    new BigDecimal("75000"), "KRW",
                    LocalDateTime.of(2024, 1, 15, 10, 30, 0), "KRW-BTC");
            given(connector.getTrades(any(), eq("BTC"), any(), eq(100)))
                    .willReturn(new TradePageResult(List.of(trade), false, null));

            SymbolCollectionResult result = collector.collectSymbol(USER_ID, apiKey, "BTC", null);

            assertThat(result.success()).isTrue();
            assertThat(result.newTradesCount()).isEqualTo(1);

            List<Trade> trades = tradeRepository.findAll();
            assertThat(trades).hasSize(1);
            assertThat(trades.get(0).getExchangeTradeId()).isEqualTo("uuid-1");
            assertThat(trades.get(0).getSymbol()).isEqualTo("BTC");
            assertThat(trades.get(0).getSide()).isEqualTo("BUY");
            assertThat(trades.get(0).getPrice()).isEqualByComparingTo(new BigDecimal("50000000"));
        }

        @Test
        @DisplayName("다중 페이지 수집 시 모든 거래가 DB에 저장된다")
        void multiPageSavesAllTrades() {
            TradeItem trade1 = new TradeItem("uuid-1", "BTC", "BUY",
                    new BigDecimal("50000000"), new BigDecimal("1.0"),
                    new BigDecimal("75000"), "KRW",
                    LocalDateTime.of(2024, 1, 15, 10, 0, 0), "KRW-BTC");
            TradeItem trade2 = new TradeItem("uuid-2", "BTC", "SELL",
                    new BigDecimal("55000000"), new BigDecimal("0.5"),
                    new BigDecimal("41250"), "KRW",
                    LocalDateTime.of(2024, 1, 15, 12, 0, 0), "KRW-BTC");

            given(connector.getTrades(any(), eq("BTC"), isNull(), eq(100)))
                    .willReturn(new TradePageResult(List.of(trade1), true, "2024-01-15T10:00:00"));
            given(connector.getTrades(any(), eq("BTC"), eq("2024-01-15T10:00:00"), eq(100)))
                    .willReturn(new TradePageResult(List.of(trade2), false, null));

            SymbolCollectionResult result = collector.collectSymbol(USER_ID, apiKey, "BTC", null);

            assertThat(result.success()).isTrue();
            assertThat(result.newTradesCount()).isEqualTo(2);
            assertThat(tradeRepository.findAll()).hasSize(2);
        }

        @Test
        @DisplayName("중복 거래는 insertIgnore로 무시되어 DB에 1건만 저장된다 (멱등성)")
        void duplicateTradeIsIgnored() {
            TradeItem trade = new TradeItem("uuid-dup", "BTC", "BUY",
                    new BigDecimal("50000000"), new BigDecimal("1.0"),
                    new BigDecimal("75000"), "KRW",
                    LocalDateTime.of(2024, 1, 15, 10, 30, 0), "KRW-BTC");
            given(connector.getTrades(any(), eq("BTC"), any(), eq(100)))
                    .willReturn(new TradePageResult(List.of(trade), false, null));

            // 첫 번째 수집
            collector.collectSymbol(USER_ID, apiKey, "BTC", null);
            // 두 번째 수집 (동일 데이터)
            SymbolCollectionResult result = collector.collectSymbol(USER_ID, apiKey, "BTC", null);

            assertThat(result.newTradesCount()).isEqualTo(0);
            assertThat(tradeRepository.findAll()).hasSize(1);
        }

        @Test
        @DisplayName("수집 후 SyncCursor가 생성된다")
        void createsSyncCursor() {
            TradeItem trade = new TradeItem("uuid-1", "ETH", "BUY",
                    new BigDecimal("3000000"), new BigDecimal("5.0"),
                    new BigDecimal("22500"), "KRW",
                    LocalDateTime.of(2024, 6, 1, 10, 0, 0), "KRW-ETH");
            given(connector.getTrades(any(), eq("ETH"), any(), eq(100)))
                    .willReturn(new TradePageResult(List.of(trade), false, null));

            collector.collectSymbol(USER_ID, apiKey, "ETH", null);

            Optional<SyncCursor> cursor = syncCursorRepository
                    .findByUserIdAndExchangeAndSymbol(USER_ID, Exchange.UPBIT, "ETH");
            assertThat(cursor).isPresent();
            assertThat(cursor.get().getLastSyncedAt())
                    .isEqualTo(LocalDateTime.of(2024, 6, 1, 10, 0, 0));
        }

        @Test
        @DisplayName("증분 수집 시 기존 SyncCursor가 갱신된다")
        void updatesExistingSyncCursor() {
            syncCursorRepository.saveAndFlush(SyncCursor.builder()
                    .userId(USER_ID)
                    .exchange(Exchange.UPBIT)
                    .symbol("BTC")
                    .lastTradeId("2024-01-01T00:00:00")
                    .lastSyncedAt(LocalDateTime.of(2024, 1, 1, 0, 0))
                    .build());

            TradeItem trade = new TradeItem("uuid-new", "BTC", "BUY",
                    new BigDecimal("60000000"), new BigDecimal("0.5"),
                    new BigDecimal("45000"), "KRW",
                    LocalDateTime.of(2024, 6, 15, 14, 0, 0), "KRW-BTC");
            given(connector.getTrades(any(), eq("BTC"), any(), eq(100)))
                    .willReturn(new TradePageResult(List.of(trade), false, null));

            collector.collectSymbol(USER_ID, apiKey, "BTC", "2024-01-01T00:00:00");

            SyncCursor updated = syncCursorRepository
                    .findByUserIdAndExchangeAndSymbol(USER_ID, Exchange.UPBIT, "BTC").orElseThrow();
            assertThat(updated.getLastSyncedAt())
                    .isEqualTo(LocalDateTime.of(2024, 6, 15, 14, 0, 0));
        }

        @Test
        @DisplayName("빈 응답 시 거래도 SyncCursor도 생성되지 않는다")
        void emptyResponseSavesNothing() {
            given(connector.getTrades(any(), eq("BTC"), any(), eq(100)))
                    .willReturn(new TradePageResult(List.of(), false, null));

            SymbolCollectionResult result = collector.collectSymbol(USER_ID, apiKey, "BTC", null);

            assertThat(result.success()).isTrue();
            assertThat(result.newTradesCount()).isEqualTo(0);
            assertThat(tradeRepository.findAll()).isEmpty();
            assertThat(syncCursorRepository.findByUserIdAndExchangeAndSymbol(
                    USER_ID, Exchange.UPBIT, "BTC")).isEmpty();
        }

        @Test
        @DisplayName("API 예외 발생 시 failure 결과를 반환한다")
        void exceptionReturnsFailure() {
            given(connector.getTrades(any(), eq("BTC"), any(), eq(100)))
                    .willThrow(new RuntimeException("API 오류"));

            SymbolCollectionResult result = collector.collectSymbol(USER_ID, apiKey, "BTC", null);

            assertThat(result.success()).isFalse();
            assertThat(result.failReason()).contains("API 오류");
        }
    }

    @Nested
    @DisplayName("syncBalances — 잔고 동기화 → DB 저장 검증")
    class SyncBalances {

        @Test
        @DisplayName("신규 자산이 DB에 저장된다")
        void savesNewAsset() {
            given(connector.getBalances(any())).willReturn(List.of(
                    new BalanceItem("BTC", new BigDecimal("1.5"), new BigDecimal("0.5"), new BigDecimal("50000000"))
            ));

            collector.syncBalances(USER_ID, apiKey);

            Optional<Asset> asset = assetRepository
                    .findByUserIdAndExchangeAndCoin(USER_ID, Exchange.UPBIT, "BTC");
            assertThat(asset).isPresent();
            assertThat(asset.get().getAmount()).isEqualByComparingTo(new BigDecimal("1.5"));
            assertThat(asset.get().getLockedAmount()).isEqualByComparingTo(new BigDecimal("0.5"));
            assertThat(asset.get().getAvgBuyPrice()).isEqualByComparingTo(new BigDecimal("50000000"));
        }

        @Test
        @DisplayName("기존 자산의 잔고가 업데이트된다")
        void updatesExistingAsset() {
            assetRepository.saveAndFlush(Asset.builder()
                    .userId(USER_ID).exchange(Exchange.UPBIT).coin("ETH")
                    .amount(new BigDecimal("5.0")).avgBuyPrice(new BigDecimal("2500000"))
                    .lockedAmount(BigDecimal.ZERO).build());

            given(connector.getBalances(any())).willReturn(List.of(
                    new BalanceItem("ETH", new BigDecimal("10.0"), new BigDecimal("1.0"), new BigDecimal("3000000"))
            ));

            collector.syncBalances(USER_ID, apiKey);

            Asset updated = assetRepository
                    .findByUserIdAndExchangeAndCoin(USER_ID, Exchange.UPBIT, "ETH").orElseThrow();
            assertThat(updated.getAmount()).isEqualByComparingTo(new BigDecimal("10.0"));
            assertThat(updated.getAvgBuyPrice()).isEqualByComparingTo(new BigDecimal("3000000"));
            assertThat(updated.getLockedAmount()).isEqualByComparingTo(new BigDecimal("1.0"));
        }

        @Test
        @DisplayName("잔고가 0인 자산은 저장하지 않는다")
        void skipsZeroBalance() {
            given(connector.getBalances(any())).willReturn(List.of(
                    new BalanceItem("XRP", BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("500"))
            ));

            collector.syncBalances(USER_ID, apiKey);

            assertThat(assetRepository.findByUserIdAndExchangeAndCoin(
                    USER_ID, Exchange.UPBIT, "XRP")).isEmpty();
        }

        @Test
        @DisplayName("여러 자산을 한 번에 동기화한다")
        void syncsMultipleAssets() {
            given(connector.getBalances(any())).willReturn(List.of(
                    new BalanceItem("BTC", new BigDecimal("1.0"), BigDecimal.ZERO, new BigDecimal("50000000")),
                    new BalanceItem("ETH", new BigDecimal("5.0"), BigDecimal.ZERO, new BigDecimal("3000000")),
                    new BalanceItem("XRP", new BigDecimal("1000"), new BigDecimal("100"), new BigDecimal("500"))
            ));

            collector.syncBalances(USER_ID, apiKey);

            assertThat(assetRepository.findAll()).hasSize(3);
        }

        @Test
        @DisplayName("API 예외 시 예외를 던지지 않는다")
        void exceptionDoesNotPropagate() {
            given(connector.getBalances(any())).willThrow(new RuntimeException("API 오류"));

            assertThatCode(() -> collector.syncBalances(USER_ID, apiKey))
                    .doesNotThrowAnyException();
        }
    }
}
