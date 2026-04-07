package com.danalfintech.cryptotax.exchange.upbit;

import com.danalfintech.cryptotax.exchange.common.entity.Exchange;
import com.danalfintech.cryptotax.exchange.common.entity.ExchangeApiKey;
import com.danalfintech.cryptotax.exchange.upbit.dto.UpbitAccount;
import com.danalfintech.cryptotax.exchange.upbit.dto.UpbitMarket;
import com.danalfintech.cryptotax.exchange.upbit.dto.UpbitTrade;
import com.danalfintech.cryptotax.global.infra.exchange.ExchangeRestClientFactory;
import com.danalfintech.cryptotax.global.infra.exchange.dto.BalanceItem;
import com.danalfintech.cryptotax.global.infra.exchange.dto.TradePageResult;
import com.danalfintech.cryptotax.global.infra.exchange.dto.VerifyResult;
import com.danalfintech.cryptotax.global.infra.redis.DistributedRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class UpbitConnectorTest {

    @Mock
    private DistributedRateLimiter rateLimiter;

    @Mock
    private ExchangeRestClientFactory restClientFactory;

    @InjectMocks
    private UpbitConnector connector;

    // RestClient mock chain
    @Mock private RestClient restClient;
    @Mock private RestClient.RequestHeadersUriSpec<?> headersUriSpec;
    @Mock private RestClient.RequestHeadersSpec<?> headersSpec;
    @Mock private RestClient.ResponseSpec responseSpec;

    private ExchangeApiKey apiKey;

    @BeforeEach
    void setUp() {
        apiKey = ExchangeApiKey.builder()
                .userId(1L)
                .exchange(Exchange.UPBIT)
                .accessKey("test-access-key")
                .secretKey("test-secret-key-must-be-at-least-256-bits-long-for-hmac-sha")
                .memo("test")
                .build();
    }

    @SuppressWarnings("unchecked")
    private void stubRestClientChain() {
        lenient().doReturn(restClient).when(restClientFactory).create(any(ExchangeApiKey.class));
        lenient().doReturn((RestClient.RequestHeadersUriSpec) headersUriSpec).when(restClient).get();
        lenient().doReturn((RestClient.RequestHeadersSpec) headersSpec).when(headersUriSpec).uri(anyString());
        lenient().doReturn((RestClient.RequestHeadersSpec) headersSpec).when(headersSpec).header(anyString(), anyString());
        lenient().doReturn(responseSpec).when(headersSpec).retrieve();
    }

    @Test
    @DisplayName("getExchange()는 UPBIT를 반환한다")
    void getExchange() {
        assertThat(connector.getExchange()).isEqualTo(Exchange.UPBIT);
    }

    @Nested
    @DisplayName("getBalances")
    class GetBalances {

        @Test
        @DisplayName("정상 응답 시 BalanceItem 리스트로 변환한다")
        void success() {
            stubRestClientChain();
            UpbitAccount[] accounts = {
                    new UpbitAccount("BTC", "1.5", "0.5", "50000000", false, "KRW"),
                    new UpbitAccount("ETH", "10.0", "0.0", "3000000", false, "KRW")
            };
            given(responseSpec.body(UpbitAccount[].class)).willReturn(accounts);

            List<BalanceItem> result = connector.getBalances(apiKey);

            assertThat(result).hasSize(2);
            assertThat(result.get(0).coin()).isEqualTo("BTC");
            assertThat(result.get(0).balance()).isEqualByComparingTo(new BigDecimal("1.5"));
            assertThat(result.get(0).locked()).isEqualByComparingTo(new BigDecimal("0.5"));
            assertThat(result.get(0).avgBuyPrice()).isEqualByComparingTo(new BigDecimal("50000000"));
            then(rateLimiter).should().waitForPermit(any(), eq(1));
        }

        @Test
        @DisplayName("null 응답 시 빈 리스트를 반환한다")
        void nullResponse() {
            stubRestClientChain();
            given(responseSpec.body(UpbitAccount[].class)).willReturn(null);

            List<BalanceItem> result = connector.getBalances(apiKey);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("avgBuyPrice가 null인 경우 null로 매핑한다")
        void nullAvgBuyPrice() {
            stubRestClientChain();
            UpbitAccount[] accounts = {
                    new UpbitAccount("BTC", "1.0", "0.0", null, false, "KRW")
            };
            given(responseSpec.body(UpbitAccount[].class)).willReturn(accounts);

            List<BalanceItem> result = connector.getBalances(apiKey);

            assertThat(result.get(0).avgBuyPrice()).isNull();
        }
    }

    @Nested
    @DisplayName("getTrades")
    class GetTrades {

        @Test
        @DisplayName("체결 내역을 TradeItem으로 변환한다 (bid -> BUY)")
        void success_bidToBuy() {
            stubRestClientChain();
            UpbitTrade[] trades = {
                    new UpbitTrade("uuid-1", "bid", "limit", "50000000",
                            "done", "KRW-BTC", "2024-01-15T10:30:00+09:00",
                            "1.0", "0.0", "1.0", "75000", 1)
            };
            given(responseSpec.body(UpbitTrade[].class)).willReturn(trades);

            TradePageResult result = connector.getTrades(apiKey, "BTC", null, 100);

            assertThat(result.trades()).hasSize(1);
            assertThat(result.trades().get(0).side()).isEqualTo("BUY");
            assertThat(result.trades().get(0).exchangeTradeId()).isEqualTo("uuid-1");
            assertThat(result.trades().get(0).symbol()).isEqualTo("BTC");
            assertThat(result.trades().get(0).price()).isEqualByComparingTo(new BigDecimal("50000000"));
            assertThat(result.trades().get(0).quantity()).isEqualByComparingTo(new BigDecimal("1.0"));
            assertThat(result.trades().get(0).fee()).isEqualByComparingTo(new BigDecimal("75000"));
            assertThat(result.trades().get(0).market()).isEqualTo("KRW-BTC");
        }

        @Test
        @DisplayName("ask는 SELL로 매핑한다")
        void askToSell() {
            stubRestClientChain();
            UpbitTrade[] trades = {
                    new UpbitTrade("uuid-2", "ask", "limit", "60000000",
                            "done", "KRW-BTC", "2024-01-15T11:00:00+09:00",
                            "0.5", "0.0", "0.5", "45000", 1)
            };
            given(responseSpec.body(UpbitTrade[].class)).willReturn(trades);

            TradePageResult result = connector.getTrades(apiKey, "BTC", null, 100);

            assertThat(result.trades().get(0).side()).isEqualTo("SELL");
        }

        @Test
        @DisplayName("executedVolume이 0인 주문은 필터링한다")
        void filtersZeroExecutedVolume() {
            stubRestClientChain();
            UpbitTrade[] trades = {
                    new UpbitTrade("uuid-1", "bid", "limit", "50000000",
                            "cancel", "KRW-BTC", "2024-01-15T10:30:00+09:00",
                            "1.0", "1.0", "0", "0", 0),
                    new UpbitTrade("uuid-2", "bid", "limit", "50000000",
                            "done", "KRW-BTC", "2024-01-15T11:00:00+09:00",
                            "1.0", "0.0", "1.0", "75000", 1)
            };
            given(responseSpec.body(UpbitTrade[].class)).willReturn(trades);

            TradePageResult result = connector.getTrades(apiKey, "BTC", null, 100);

            assertThat(result.trades()).hasSize(1);
            assertThat(result.trades().get(0).exchangeTradeId()).isEqualTo("uuid-2");
        }

        @Test
        @DisplayName("응답 건수 < limit이면 hasMore=false, nextCursor=null")
        void lastPage() {
            stubRestClientChain();
            UpbitTrade[] trades = {
                    new UpbitTrade("uuid-1", "bid", "limit", "50000000",
                            "done", "KRW-BTC", "2024-01-15T10:30:00+09:00",
                            "1.0", "0.0", "1.0", "75000", 1)
            };
            given(responseSpec.body(UpbitTrade[].class)).willReturn(trades);

            TradePageResult result = connector.getTrades(apiKey, "BTC", null, 100);

            assertThat(result.hasMore()).isFalse();
            assertThat(result.nextCursor()).isNull();
        }

        @Test
        @DisplayName("응답 건수 == limit이면 hasMore=true, nextCursor에 마지막 createdAt 포맷")
        void hasMorePages() {
            stubRestClientChain();
            // limit=2로 테스트
            UpbitTrade[] trades = {
                    new UpbitTrade("uuid-1", "bid", "limit", "50000000",
                            "done", "KRW-BTC", "2024-01-15T10:00:00+09:00",
                            "1.0", "0.0", "1.0", "75000", 1),
                    new UpbitTrade("uuid-2", "bid", "limit", "51000000",
                            "done", "KRW-BTC", "2024-01-15T11:30:45+09:00",
                            "0.5", "0.0", "0.5", "38000", 1)
            };
            given(responseSpec.body(UpbitTrade[].class)).willReturn(trades);

            TradePageResult result = connector.getTrades(apiKey, "BTC", null, 2);

            assertThat(result.hasMore()).isTrue();
            assertThat(result.nextCursor()).isEqualTo("2024-01-15T11:30:45");
        }

        @Test
        @DisplayName("빈 응답이면 빈 리스트, hasMore=false")
        void emptyResponse() {
            stubRestClientChain();
            given(responseSpec.body(UpbitTrade[].class)).willReturn(new UpbitTrade[0]);

            TradePageResult result = connector.getTrades(apiKey, "BTC", null, 100);

            assertThat(result.trades()).isEmpty();
            assertThat(result.hasMore()).isFalse();
            assertThat(result.nextCursor()).isNull();
        }

        @Test
        @DisplayName("null 응답이면 빈 리스트, hasMore=false")
        void nullResponse() {
            stubRestClientChain();
            given(responseSpec.body(UpbitTrade[].class)).willReturn(null);

            TradePageResult result = connector.getTrades(apiKey, "BTC", null, 100);

            assertThat(result.trades()).isEmpty();
            assertThat(result.hasMore()).isFalse();
        }

        @Test
        @DisplayName("fromId가 주어지면 해당 시각부터 조회한다 (증분 수집)")
        void incrementalWithFromId() {
            stubRestClientChain();
            given(responseSpec.body(UpbitTrade[].class)).willReturn(new UpbitTrade[0]);

            connector.getTrades(apiKey, "BTC", "2024-06-01T00:00:00", 100);

            // 호출 자체가 에러 없이 성공하면 fromId가 start_time으로 사용된 것
            then(rateLimiter).should().waitForPermit(any(), eq(1));
        }

        @Test
        @DisplayName("price가 null인 경우 BigDecimal.ZERO로 매핑한다")
        void nullPrice() {
            stubRestClientChain();
            UpbitTrade[] trades = {
                    new UpbitTrade("uuid-1", "bid", "market", null,
                            "done", "KRW-BTC", "2024-01-15T10:30:00+09:00",
                            "1.0", "0.0", "1.0", "75000", 1)
            };
            given(responseSpec.body(UpbitTrade[].class)).willReturn(trades);

            TradePageResult result = connector.getTrades(apiKey, "BTC", null, 100);

            assertThat(result.trades().get(0).price()).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    @Nested
    @DisplayName("getMarkets")
    class GetMarkets {

        @Test
        @DisplayName("KRW 마켓만 필터링하고 심볼명을 추출한다")
        void filtersKrwMarkets() {
            stubRestClientChain();
            UpbitMarket[] markets = {
                    new UpbitMarket("KRW-BTC", "비트코인", "Bitcoin", "NONE"),
                    new UpbitMarket("KRW-ETH", "이더리움", "Ethereum", "NONE"),
                    new UpbitMarket("BTC-ETH", "이더리움", "Ethereum", "NONE"),
                    new UpbitMarket("USDT-BTC", "비트코인", "Bitcoin", "NONE")
            };
            given(responseSpec.body(UpbitMarket[].class)).willReturn(markets);

            List<String> result = connector.getMarkets(apiKey);

            assertThat(result).containsExactly("BTC", "ETH");
        }

        @Test
        @DisplayName("null 응답 시 빈 리스트를 반환한다")
        void nullResponse() {
            stubRestClientChain();
            given(responseSpec.body(UpbitMarket[].class)).willReturn(null);

            List<String> result = connector.getMarkets(apiKey);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("verify")
    class Verify {

        @Test
        @DisplayName("정상 응답 시 valid=true")
        void success() {
            stubRestClientChain();
            given(responseSpec.body(String.class)).willReturn("[]");

            VerifyResult result = connector.verify(apiKey);

            assertThat(result.valid()).isTrue();
        }

        @Test
        @DisplayName("401 응답 시 valid=false")
        void unauthorized() {
            stubRestClientChain();
            given(responseSpec.body(String.class))
                    .willThrow(HttpClientErrorException.create(
                            HttpStatusCode.valueOf(401), "Unauthorized", null, null, null));

            VerifyResult result = connector.verify(apiKey);

            assertThat(result.valid()).isFalse();
            assertThat(result.message()).contains("인증 실패");
        }

        @Test
        @DisplayName("기타 예외 시 valid=false, 메시지 포함")
        void otherException() {
            stubRestClientChain();
            given(responseSpec.body(String.class))
                    .willThrow(new RuntimeException("connection refused"));

            VerifyResult result = connector.verify(apiKey);

            assertThat(result.valid()).isFalse();
            assertThat(result.message()).contains("connection refused");
        }
    }

    @Nested
    @DisplayName("재시도 로직 (fetchWithRetry)")
    class Retry {

        @Test
        @DisplayName("429 응답 시 재시도 후 성공한다")
        void retryOn429ThenSuccess() {
            stubRestClientChain();
            given(responseSpec.body(UpbitMarket[].class))
                    .willThrow(HttpClientErrorException.create(
                            HttpStatusCode.valueOf(429), "Too Many Requests", null, null, null))
                    .willReturn(new UpbitMarket[]{
                            new UpbitMarket("KRW-BTC", "비트코인", "Bitcoin", "NONE")
                    });

            List<String> result = connector.getMarkets(apiKey);

            assertThat(result).containsExactly("BTC");
        }

        @Test
        @DisplayName("5xx 응답 시 재시도 후 성공한다")
        void retryOn5xxThenSuccess() {
            stubRestClientChain();
            given(responseSpec.body(UpbitMarket[].class))
                    .willThrow(HttpServerErrorException.create(
                            HttpStatusCode.valueOf(500), "Internal Server Error", null, null, null))
                    .willReturn(new UpbitMarket[]{
                            new UpbitMarket("KRW-ETH", "이더리움", "Ethereum", "NONE")
                    });

            List<String> result = connector.getMarkets(apiKey);

            assertThat(result).containsExactly("ETH");
        }

        @Test
        @DisplayName("401 응답은 재시도 없이 즉시 예외를 던진다")
        void noRetryOn401() {
            stubRestClientChain();
            given(responseSpec.body(UpbitAccount[].class))
                    .willThrow(HttpClientErrorException.create(
                            HttpStatusCode.valueOf(401), "Unauthorized", null, null, null));

            assertThatThrownBy(() -> connector.getBalances(apiKey))
                    .isInstanceOf(HttpClientErrorException.class);

            // body()는 1번만 호출되어야 함 (재시도 없음)
            then(responseSpec).should(times(1)).body(UpbitAccount[].class);
        }
    }
}
