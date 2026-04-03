package com.danalfintech.cryptotax.exchange.upbit;

import com.danalfintech.cryptotax.exchange.common.entity.ExchangeApiKey;
import com.danalfintech.cryptotax.exchange.upbit.dto.UpbitAccount;
import com.danalfintech.cryptotax.exchange.upbit.dto.UpbitMarket;
import com.danalfintech.cryptotax.exchange.upbit.dto.UpbitTrade;
import com.danalfintech.cryptotax.global.infra.exchange.ExchangeConnector;
import com.danalfintech.cryptotax.global.infra.exchange.dto.*;
import com.danalfintech.cryptotax.global.infra.exchange.ExchangeContext;
import com.danalfintech.cryptotax.global.infra.exchange.ExchangeRestClientFactory;
import com.danalfintech.cryptotax.global.infra.redis.DistributedRateLimiter;
import com.danalfintech.cryptotax.exchange.common.entity.Exchange;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class UpbitConnector implements ExchangeConnector {

    private static final String BASE_URL = "https://api.upbit.com";
    private static final int DEFAULT_LIMIT = 100;
    /** 업비트 /v1/orders/closed는 start_time 없으면 최근 7일만 조회. 전체 수집 시 이 기준일부터 시작 */
    private static final String FULL_COLLECTION_START = "2017-01-01T00:00:00";
    private static final DateTimeFormatter CURSOR_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final DistributedRateLimiter rateLimiter;
    private final ExchangeRestClientFactory restClientFactory;

    @Override
    public Exchange getExchange() {
        return Exchange.UPBIT;
    }

    @Override
    public List<BalanceItem> getBalances(ExchangeApiKey key) {
        UpbitAccount[] accounts = fetchWithRetry(() -> {
            rateLimiter.waitForPermit(ExchangeContext.of(Exchange.UPBIT), 1);
            return restClientFactory.create(key)
                    .get()
                    .uri(BASE_URL + "/v1/accounts")
                    .header("Authorization", "Bearer " + generateToken(key, null))
                    .retrieve()
                    .body(UpbitAccount[].class);
        });

        if (accounts == null) return List.of();

        return Arrays.stream(accounts)
                .map(a -> new BalanceItem(
                        a.currency(),
                        new BigDecimal(a.balance()),
                        new BigDecimal(a.locked()),
                        a.avgBuyPrice() != null ? new BigDecimal(a.avgBuyPrice()) : null
                ))
                .toList();
    }

    /**
     * 업비트 체결 주문 조회.
     * /v1/orders/closed API는 start_time/end_time 기반 시간 범위 페이지네이션 사용.
     * fromId가 null이면 FULL_COLLECTION_START부터 시작 (전체 수집).
     * fromId가 있으면 해당 시각 이후부터 조회 (증분 수집 — ISO 8601 timestamp).
     */
    @Override
    public TradePageResult getTrades(ExchangeApiKey key, String symbol, String fromId, int limit) {
        String market = "KRW-" + symbol;
        String startTime = (fromId != null && !fromId.isEmpty()) ? fromId : FULL_COLLECTION_START;

        String queryString = buildTradeQueryString(market, startTime, limit);

        UpbitTrade[] trades = fetchWithRetry(() -> {
            rateLimiter.waitForPermit(ExchangeContext.of(Exchange.UPBIT), 1);
            return restClientFactory.create(key)
                    .get()
                    .uri(BASE_URL + "/v1/orders/closed?" + queryString)
                    .header("Authorization", "Bearer " + generateToken(key, queryString))
                    .retrieve()
                    .body(UpbitTrade[].class);
        });

        if (trades == null || trades.length == 0) {
            return new TradePageResult(List.of(), false, null);
        }

        // done + cancel 모두 포함 (cancel도 부분 체결분이 있을 수 있음)
        List<TradeItem> items = Arrays.stream(trades)
                .filter(t -> new BigDecimal(t.executedVolume()).compareTo(BigDecimal.ZERO) > 0)
                .map(t -> new TradeItem(
                        t.uuid(),
                        symbol,
                        "bid".equals(t.side()) ? "BUY" : "SELL",
                        t.price() != null ? new BigDecimal(t.price()) : BigDecimal.ZERO,
                        new BigDecimal(t.executedVolume()),
                        t.paidFee() != null ? new BigDecimal(t.paidFee()) : BigDecimal.ZERO,
                        "KRW",
                        parseDateTime(t.createdAt()),
                        market
                ))
                .toList();

        // 꼬리 확인: 응답 건수 < limit이면 마지막 페이지
        boolean hasMore = trades.length >= limit;
        // 다음 페이지 커서: 마지막 주문의 created_at을 고정 포맷으로 통일
        String nextCursor = hasMore
                ? parseDateTime(trades[trades.length - 1].createdAt()).format(CURSOR_FORMAT)
                : null;

        return new TradePageResult(items, hasMore, nextCursor);
    }

    @Override
    public List<String> getMarkets(ExchangeApiKey key) {
        UpbitMarket[] markets = fetchWithRetry(() -> {
            rateLimiter.waitForPermit(ExchangeContext.of(Exchange.UPBIT), 1);
            return restClientFactory.create(key)
                    .get()
                    .uri(BASE_URL + "/v1/market/all")
                    .retrieve()
                    .body(UpbitMarket[].class);
        });

        if (markets == null) return List.of();

        return Arrays.stream(markets)
                .map(UpbitMarket::market)
                .filter(m -> m.startsWith("KRW-"))
                .map(m -> m.substring(4))
                .toList();
    }

    @Override
    public VerifyResult verify(ExchangeApiKey key) {
        try {
            rateLimiter.waitForPermit(ExchangeContext.of(Exchange.UPBIT), 1);
            restClientFactory.create(key)
                    .get()
                    .uri(BASE_URL + "/v1/api_keys")
                    .header("Authorization", "Bearer " + generateToken(key, null))
                    .retrieve()
                    .body(String.class);
            return new VerifyResult(true, "유효한 API 키");
        } catch (HttpClientErrorException.Unauthorized e) {
            return new VerifyResult(false, "인증 실패: 유효하지 않은 API 키");
        } catch (Exception e) {
            return new VerifyResult(false, "검증 실패: " + e.getMessage());
        }
    }

    /**
     * Layer 1 재시도 로직
     * 429: 지수 백오프 1→2→4→8→16초, 최대 5회
     * 500: 2초 간격 3회, 초과 시 SKIP
     * 401: 재시도 없음, 즉시 예외
     * Timeout(10초): 5초 간격 3회
     */
    private <T> T fetchWithRetry(ApiCall<T> apiCall) {
        int maxRetries429 = 5;
        int maxRetries5xx = 3;
        int maxRetriesTimeout = 3;

        int attempt429 = 0;
        int attempt5xx = 0;
        int attemptTimeout = 0;

        while (true) {
            try {
                return apiCall.execute();
            } catch (HttpClientErrorException e) {
                if (e.getStatusCode().value() == 401) {
                    throw e;
                }
                if (e.getStatusCode().value() == 429) {
                    attempt429++;
                    if (attempt429 > maxRetries429) {
                        throw e;
                    }
                    long backoff = (long) Math.pow(2, attempt429 - 1) * 1000;
                    log.warn("업비트 429 응답, {}초 후 재시도 ({}/{})", backoff / 1000, attempt429, maxRetries429);
                    sleep(backoff);
                } else {
                    throw e;
                }
            } catch (HttpServerErrorException e) {
                attempt5xx++;
                if (attempt5xx > maxRetries5xx) {
                    throw e;
                }
                log.warn("업비트 5xx 응답, 2초 후 재시도 ({}/{})", attempt5xx, maxRetries5xx);
                sleep(2000);
            } catch (Exception e) {
                if (isTimeout(e)) {
                    attemptTimeout++;
                    if (attemptTimeout > maxRetriesTimeout) {
                        throw new RuntimeException("업비트 API 타임아웃 초과", e);
                    }
                    log.warn("업비트 타임아웃, 5초 후 재시도 ({}/{})", attemptTimeout, maxRetriesTimeout);
                    sleep(5000);
                } else {
                    throw e;
                }
            }
        }
    }

    private String generateToken(ExchangeApiKey key, String queryString) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("access_key", key.getAccessKey());
        claims.put("nonce", UUID.randomUUID().toString());

        if (queryString != null && !queryString.isEmpty()) {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-512");
                byte[] hash = md.digest(queryString.getBytes(StandardCharsets.UTF_8));
                String queryHash = bytesToHex(hash);
                claims.put("query_hash", queryHash);
                claims.put("query_hash_alg", "SHA512");
            } catch (Exception e) {
                throw new RuntimeException("쿼리 해시 생성 실패", e);
            }
        }

        return Jwts.builder()
                .claims(claims)
                .signWith(Keys.hmacShaKeyFor(key.getSecretKey().getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    /**
     * 업비트 /v1/orders/closed 쿼리 파라미터 생성.
     * state 필터 없이 호출하면 done+cancel 모두 반환.
     * start_time으로 시간 범위 지정, order_by=asc로 오래된 것부터.
     */
    private String buildTradeQueryString(String market, String startTime, int limit) {
        StringBuilder sb = new StringBuilder();
        sb.append("market=").append(market);
        sb.append("&states[]=done&states[]=cancel");
        sb.append("&start_time=").append(URLEncoder.encode(startTime, StandardCharsets.UTF_8));
        sb.append("&limit=").append(limit);
        sb.append("&order_by=asc");
        return sb.toString();
    }



    private LocalDateTime parseDateTime(String dateTimeStr) {
        try {
            OffsetDateTime odt = OffsetDateTime.parse(dateTimeStr, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            return odt.toLocalDateTime();
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }

    private boolean isTimeout(Exception e) {
        return e.getMessage() != null && (
                e.getMessage().contains("timeout") ||
                e.getMessage().contains("Timeout") ||
                e.getMessage().contains("timed out")
        );
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    @FunctionalInterface
    private interface ApiCall<T> {
        T execute();
    }
}
