package com.danalfintech.cryptotax.exchange.bithumb;

import com.danalfintech.cryptotax.collection.domain.SyncCursor;
import com.danalfintech.cryptotax.collection.domain.SyncCursorRepository;
import com.danalfintech.cryptotax.exchange.common.entity.Exchange;
import com.danalfintech.cryptotax.exchange.common.entity.ExchangeApiKey;
import com.danalfintech.cryptotax.global.infra.exchange.dto.*;
import com.danalfintech.cryptotax.portfolio.domain.Asset;
import com.danalfintech.cryptotax.portfolio.domain.AssetRepository;
import com.danalfintech.cryptotax.portfolio.domain.TradeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class BithumbCollectorTest {

    @Mock private BithumbConnector connector;
    @Mock private TradeRepository tradeRepository;
    @Mock private AssetRepository assetRepository;
    @Mock private SyncCursorRepository syncCursorRepository;

    @InjectMocks
    private BithumbCollector collector;

    private ExchangeApiKey apiKey;
    private static final Long USER_ID = 1L;

    @BeforeEach
    void setUp() {
        apiKey = ExchangeApiKey.builder()
                .userId(USER_ID)
                .exchange(Exchange.BITHUMB)
                .accessKey("test-access")
                .secretKey("test-secret-key-must-be-at-least-256-bits")
                .build();
    }

    @Test
    @DisplayName("getExchange()는 BITHUMB를 반환한다")
    void getExchange() {
        assertThat(collector.getExchange()).isEqualTo(Exchange.BITHUMB);
    }

    @Nested
    @DisplayName("collectSymbol")
    class CollectSymbol {

        @Test
        @DisplayName("단일 페이지 수집 성공 시 success 결과를 반환한다")
        void singlePage_success() {
            // desc 정렬: 최신 거래가 먼저
            TradeItem trade = new TradeItem("uuid-1", "BTC", "BUY",
                    new BigDecimal("50000000"), new BigDecimal("1.0"),
                    new BigDecimal("75000"), "KRW",
                    LocalDateTime.of(2024, 1, 15, 10, 30, 0), "KRW-BTC");
            TradePageResult page = new TradePageResult(List.of(trade), false, null);

            // 빗썸은 페이지 기반: fromId=null → pageCursor=null
            given(connector.getTrades(apiKey, "BTC", null, 100)).willReturn(page);
            given(tradeRepository.insertIgnore(anyLong(), anyString(), anyString(), anyString(),
                    anyString(), any(), any(), any(), anyString(), any(), anyString())).willReturn(1);
            given(syncCursorRepository.findByUserIdAndExchangeAndSymbol(USER_ID, Exchange.BITHUMB, "BTC"))
                    .willReturn(Optional.empty());

            SymbolCollectionResult result = collector.collectSymbol(USER_ID, apiKey, "BTC", null);

            assertThat(result.success()).isTrue();
            assertThat(result.symbol()).isEqualTo("BTC");
            assertThat(result.newTradesCount()).isEqualTo(1);
            then(syncCursorRepository).should().save(any(SyncCursor.class));
        }

        @Test
        @DisplayName("다중 페이지 수집 시 모든 페이지를 순회한다")
        void multiplePages() {
            // desc 정렬: page 1 = 최신, page 2 = 과거
            TradeItem trade1 = new TradeItem("uuid-1", "BTC", "BUY",
                    new BigDecimal("55000000"), new BigDecimal("0.5"),
                    new BigDecimal("41250"), "KRW",
                    LocalDateTime.of(2024, 1, 15, 12, 0, 0), "KRW-BTC");
            TradeItem trade2 = new TradeItem("uuid-2", "BTC", "SELL",
                    new BigDecimal("50000000"), new BigDecimal("1.0"),
                    new BigDecimal("75000"), "KRW",
                    LocalDateTime.of(2024, 1, 15, 10, 0, 0), "KRW-BTC");

            // page 1 → hasMore=true, nextCursor="2"
            TradePageResult page1 = new TradePageResult(List.of(trade1), true, "2");
            TradePageResult page2 = new TradePageResult(List.of(trade2), false, null);

            given(connector.getTrades(apiKey, "BTC", null, 100)).willReturn(page1);
            given(connector.getTrades(apiKey, "BTC", "2", 100)).willReturn(page2);
            given(tradeRepository.insertIgnore(anyLong(), anyString(), anyString(), anyString(),
                    anyString(), any(), any(), any(), anyString(), any(), anyString())).willReturn(1);
            given(syncCursorRepository.findByUserIdAndExchangeAndSymbol(USER_ID, Exchange.BITHUMB, "BTC"))
                    .willReturn(Optional.empty());

            SymbolCollectionResult result = collector.collectSymbol(USER_ID, apiKey, "BTC", null);

            assertThat(result.success()).isTrue();
            assertThat(result.newTradesCount()).isEqualTo(2);
            then(connector).should(times(2)).getTrades(eq(apiKey), eq("BTC"), any(), eq(100));
        }

        @Test
        @DisplayName("증분 수집 시 cutoff 이전 거래를 만나면 조기 중단한다")
        void incrementalCollection_earlyTermination() {
            String fromCursor = "2024-06-01T00:00:00";

            // desc 정렬: 첫 거래는 cutoff 이후(새 거래), 두 번째는 cutoff 이전(기존 거래)
            TradeItem newTrade = new TradeItem("uuid-new", "BTC", "BUY",
                    new BigDecimal("60000000"), new BigDecimal("0.5"),
                    new BigDecimal("45000"), "KRW",
                    LocalDateTime.of(2024, 6, 15, 14, 0, 0), "KRW-BTC");
            TradeItem oldTrade = new TradeItem("uuid-old", "BTC", "SELL",
                    new BigDecimal("55000000"), new BigDecimal("1.0"),
                    new BigDecimal("82500"), "KRW",
                    LocalDateTime.of(2024, 5, 30, 10, 0, 0), "KRW-BTC");

            TradePageResult page = new TradePageResult(List.of(newTrade, oldTrade), true, "2");

            given(connector.getTrades(apiKey, "BTC", null, 100)).willReturn(page);
            given(tradeRepository.insertIgnore(anyLong(), anyString(), anyString(), anyString(),
                    anyString(), any(), any(), any(), anyString(), any(), anyString())).willReturn(1);
            given(syncCursorRepository.findByUserIdAndExchangeAndSymbol(USER_ID, Exchange.BITHUMB, "BTC"))
                    .willReturn(Optional.empty());

            SymbolCollectionResult result = collector.collectSymbol(USER_ID, apiKey, "BTC", fromCursor);

            assertThat(result.success()).isTrue();
            // oldTrade는 cutoff 이전이므로 insert하지 않음 → 1건만
            assertThat(result.newTradesCount()).isEqualTo(1);
            // 조기 중단으로 page 2는 호출하지 않음
            then(connector).should(times(1)).getTrades(eq(apiKey), eq("BTC"), any(), eq(100));
        }

        @Test
        @DisplayName("증분 수집 시 빈 페이지면 커서를 갱신하지 않는다")
        void incrementalEmpty() {
            String fromCursor = "2024-06-01T00:00:00";
            TradePageResult emptyPage = new TradePageResult(List.of(), false, null);

            given(connector.getTrades(apiKey, "BTC", null, 100)).willReturn(emptyPage);

            SymbolCollectionResult result = collector.collectSymbol(USER_ID, apiKey, "BTC", fromCursor);

            assertThat(result.success()).isTrue();
            assertThat(result.newTradesCount()).isEqualTo(0);
            then(syncCursorRepository).should(never()).save(any());
        }

        @Test
        @DisplayName("중복 거래는 insertIgnore가 0을 반환하여 카운트되지 않는다")
        void duplicateTradesNotCounted() {
            TradeItem trade = new TradeItem("uuid-dup", "BTC", "BUY",
                    new BigDecimal("50000000"), new BigDecimal("1.0"),
                    new BigDecimal("75000"), "KRW",
                    LocalDateTime.of(2024, 1, 15, 10, 30, 0), "KRW-BTC");
            TradePageResult page = new TradePageResult(List.of(trade), false, null);

            given(connector.getTrades(apiKey, "BTC", null, 100)).willReturn(page);
            given(tradeRepository.insertIgnore(anyLong(), anyString(), anyString(), anyString(),
                    anyString(), any(), any(), any(), anyString(), any(), anyString())).willReturn(0);
            given(syncCursorRepository.findByUserIdAndExchangeAndSymbol(USER_ID, Exchange.BITHUMB, "BTC"))
                    .willReturn(Optional.empty());

            SymbolCollectionResult result = collector.collectSymbol(USER_ID, apiKey, "BTC", null);

            assertThat(result.success()).isTrue();
            assertThat(result.newTradesCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("커서는 가장 최신 거래(첫 번째 거래)의 시각으로 갱신된다")
        void cursorUpdatedToNewestTrade() {
            // desc 정렬: trade1이 최신, trade2가 과거
            TradeItem trade1 = new TradeItem("uuid-1", "BTC", "BUY",
                    new BigDecimal("60000000"), new BigDecimal("0.5"),
                    new BigDecimal("45000"), "KRW",
                    LocalDateTime.of(2024, 6, 15, 14, 0, 0), "KRW-BTC");
            TradeItem trade2 = new TradeItem("uuid-2", "BTC", "SELL",
                    new BigDecimal("55000000"), new BigDecimal("1.0"),
                    new BigDecimal("82500"), "KRW",
                    LocalDateTime.of(2024, 6, 10, 10, 0, 0), "KRW-BTC");

            TradePageResult page = new TradePageResult(List.of(trade1, trade2), false, null);

            given(connector.getTrades(apiKey, "BTC", null, 100)).willReturn(page);
            given(tradeRepository.insertIgnore(anyLong(), anyString(), anyString(), anyString(),
                    anyString(), any(), any(), any(), anyString(), any(), anyString())).willReturn(1);
            given(syncCursorRepository.findByUserIdAndExchangeAndSymbol(USER_ID, Exchange.BITHUMB, "BTC"))
                    .willReturn(Optional.empty());

            collector.collectSymbol(USER_ID, apiKey, "BTC", null);

            ArgumentCaptor<SyncCursor> captor = ArgumentCaptor.forClass(SyncCursor.class);
            then(syncCursorRepository).should().save(captor.capture());
            SyncCursor saved = captor.getValue();
            // 가장 최신 거래의 시각이 커서로 설정되어야 함
            assertThat(saved.getLastSyncedAt())
                    .isEqualTo(LocalDateTime.of(2024, 6, 15, 14, 0, 0));
        }

        @Test
        @DisplayName("기존 SyncCursor가 있으면 update한다")
        void updatesExistingSyncCursor() {
            TradeItem trade = new TradeItem("uuid-1", "BTC", "BUY",
                    new BigDecimal("50000000"), new BigDecimal("1.0"),
                    new BigDecimal("75000"), "KRW",
                    LocalDateTime.of(2024, 6, 1, 10, 0, 0), "KRW-BTC");
            TradePageResult page = new TradePageResult(List.of(trade), false, null);

            SyncCursor existingCursor = SyncCursor.builder()
                    .userId(USER_ID)
                    .exchange(Exchange.BITHUMB)
                    .symbol("BTC")
                    .lastTradeId("2024-01-01T00:00:00")
                    .lastSyncedAt(LocalDateTime.of(2024, 1, 1, 0, 0, 0))
                    .build();

            given(connector.getTrades(apiKey, "BTC", null, 100)).willReturn(page);
            given(tradeRepository.insertIgnore(anyLong(), anyString(), anyString(), anyString(),
                    anyString(), any(), any(), any(), anyString(), any(), anyString())).willReturn(1);
            given(syncCursorRepository.findByUserIdAndExchangeAndSymbol(USER_ID, Exchange.BITHUMB, "BTC"))
                    .willReturn(Optional.of(existingCursor));

            collector.collectSymbol(USER_ID, apiKey, "BTC", null);

            assertThat(existingCursor.getLastSyncedAt())
                    .isEqualTo(LocalDateTime.of(2024, 6, 1, 10, 0, 0));
            then(syncCursorRepository).should().save(existingCursor);
        }

        @Test
        @DisplayName("수집 중 예외 발생 시 failure 결과를 반환한다")
        void exceptionReturnsFailure() {
            given(connector.getTrades(eq(apiKey), eq("BTC"), any(), eq(100)))
                    .willThrow(new RuntimeException("API 오류"));

            SymbolCollectionResult result = collector.collectSymbol(USER_ID, apiKey, "BTC", null);

            assertThat(result.success()).isFalse();
            assertThat(result.symbol()).isEqualTo("BTC");
            assertThat(result.failReason()).contains("API 오류");
        }

        @Test
        @DisplayName("빈 페이지 응답 시 SyncCursor를 갱신하지 않는다")
        void emptyPageDoesNotUpdateCursor() {
            TradePageResult emptyPage = new TradePageResult(List.of(), false, null);
            given(connector.getTrades(apiKey, "BTC", null, 100)).willReturn(emptyPage);

            collector.collectSymbol(USER_ID, apiKey, "BTC", null);

            then(syncCursorRepository).should(never()).save(any());
        }
    }

    @Nested
    @DisplayName("syncBalances")
    class SyncBalances {

        @Test
        @DisplayName("신규 자산을 생성한다")
        void createsNewAsset() {
            List<BalanceItem> balances = List.of(
                    new BalanceItem("BTC", new BigDecimal("1.5"), new BigDecimal("0.5"), new BigDecimal("50000000"))
            );
            given(connector.getBalances(apiKey)).willReturn(balances);
            given(assetRepository.findByUserIdAndExchangeAndCoin(USER_ID, Exchange.BITHUMB, "BTC"))
                    .willReturn(Optional.empty());

            collector.syncBalances(USER_ID, apiKey);

            ArgumentCaptor<Asset> captor = ArgumentCaptor.forClass(Asset.class);
            then(assetRepository).should().save(captor.capture());
            Asset saved = captor.getValue();
            assertThat(saved.getCoin()).isEqualTo("BTC");
            assertThat(saved.getAmount()).isEqualByComparingTo(new BigDecimal("1.5"));
            assertThat(saved.getLockedAmount()).isEqualByComparingTo(new BigDecimal("0.5"));
        }

        @Test
        @DisplayName("기존 자산의 잔고를 업데이트한다")
        void updatesExistingAsset() {
            List<BalanceItem> balances = List.of(
                    new BalanceItem("ETH", new BigDecimal("10.0"), new BigDecimal("0.0"), new BigDecimal("3000000"))
            );
            Asset existingAsset = Asset.builder()
                    .userId(USER_ID)
                    .exchange(Exchange.BITHUMB)
                    .coin("ETH")
                    .amount(new BigDecimal("5.0"))
                    .avgBuyPrice(new BigDecimal("2500000"))
                    .lockedAmount(BigDecimal.ZERO)
                    .build();

            given(connector.getBalances(apiKey)).willReturn(balances);
            given(assetRepository.findByUserIdAndExchangeAndCoin(USER_ID, Exchange.BITHUMB, "ETH"))
                    .willReturn(Optional.of(existingAsset));

            collector.syncBalances(USER_ID, apiKey);

            assertThat(existingAsset.getAmount()).isEqualByComparingTo(new BigDecimal("10.0"));
            assertThat(existingAsset.getAvgBuyPrice()).isEqualByComparingTo(new BigDecimal("3000000"));
            then(assetRepository).should().save(existingAsset);
        }

        @Test
        @DisplayName("잔고가 0인 자산은 무시한다")
        void skipsZeroBalance() {
            List<BalanceItem> balances = List.of(
                    new BalanceItem("XRP", BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("500"))
            );
            given(connector.getBalances(apiKey)).willReturn(balances);

            collector.syncBalances(USER_ID, apiKey);

            then(assetRepository).should(never()).save(any());
        }

        @Test
        @DisplayName("예외 발생 시 로그만 남기고 정상 종료한다")
        void exceptionDoesNotPropagate() {
            given(connector.getBalances(apiKey)).willThrow(new RuntimeException("API 오류"));

            assertThatCode(() -> collector.syncBalances(USER_ID, apiKey))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("여러 자산을 한 번에 동기화한다")
        void syncsMultipleAssets() {
            List<BalanceItem> balances = List.of(
                    new BalanceItem("BTC", new BigDecimal("1.0"), BigDecimal.ZERO, new BigDecimal("50000000")),
                    new BalanceItem("ETH", new BigDecimal("5.0"), BigDecimal.ZERO, new BigDecimal("3000000"))
            );
            given(connector.getBalances(apiKey)).willReturn(balances);
            given(assetRepository.findByUserIdAndExchangeAndCoin(eq(USER_ID), eq(Exchange.BITHUMB), anyString()))
                    .willReturn(Optional.empty());

            collector.syncBalances(USER_ID, apiKey);

            then(assetRepository).should(times(2)).save(any(Asset.class));
        }
    }
}
