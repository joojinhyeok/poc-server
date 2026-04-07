package com.danalfintech.cryptotax.exchange.bithumb;

import com.danalfintech.cryptotax.exchange.bithumb.dto.BithumbAccount;
import com.danalfintech.cryptotax.exchange.bithumb.dto.BithumbMarket;
import com.danalfintech.cryptotax.exchange.bithumb.dto.BithumbTrade;
import com.danalfintech.cryptotax.exchange.common.entity.Exchange;
import com.danalfintech.cryptotax.exchange.common.entity.ExchangeApiKey;
import com.danalfintech.cryptotax.global.infra.exchange.ExchangeConnector;
import com.danalfintech.cryptotax.global.infra.exchange.ExchangeContext;
import com.danalfintech.cryptotax.global.infra.exchange.ExchangeRestClientFactory;
import com.danalfintech.cryptotax.global.infra.exchange.dto.*;
import com.danalfintech.cryptotax.global.error.BusinessException;
import com.danalfintech.cryptotax.global.error.ErrorCode;
import com.danalfintech.cryptotax.global.infra.redis.DistributedRateLimiter;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeoutException;

@Slf4j
@Component
@RequiredArgsConstructor
public class BithumbConnector implements ExchangeConnector {

    private static final String BASE_URL = "https://api.bithumb.com";

    private final DistributedRateLimiter rateLimiter;
    private final ExchangeRestClientFactory restClientFactory;

    @Override
    public Exchange getExchange() {
        return Exchange.BITHUMB;
    }

    @Override
    public List<BalanceItem> getBalances(ExchangeApiKey key) {
        BithumbAccount[] accounts = fetchWithRetry(() -> {
            rateLimiter.waitForPermit(ExchangeContext.of(Exchange.BITHUMB), 1);
            return restClientFactory.create(key)
                    .get()
                    .uri(BASE_URL + "/v1/accounts")
                    .header("Authorization", "Bearer " + generateToken(key, null))
                    .retrieve()
                    .body(BithumbAccount[].class);
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
     * 빗썸 체결 주문 조회.
     * /v1/orders API는 page 기반 페이지네이션 사용 (start_time 미지원).
     * fromId는 페이지 번호 문자열 (null이면 1페이지부터 시작).
     * order_by=asc로 오래된 것부터 조회하여 전체 수집 보장.
     */
    @Override
    public TradePageResult getTrades(ExchangeApiKey key, String symbol, String fromId, int limit) {
        int page = (fromId != null && !fromId.isEmpty()) ? Integer.parseInt(fromId) : 1;
        String market = "KRW-" + symbol;

        String queryString = buildTradeQueryString(market, page, limit);

        BithumbTrade[] trades = fetchWithRetry(() -> {
            rateLimiter.waitForPermit(ExchangeContext.of(Exchange.BITHUMB), 1);
            return restClientFactory.create(key)
                    .get()
                    .uri(BASE_URL + "/v1/orders?" + queryString)
                    .header("Authorization", "Bearer " + generateToken(key, queryString))
                    .retrieve()
                    .body(BithumbTrade[].class);
        });

        if (trades == null || trades.length == 0) {
            return new TradePageResult(List.of(), false, null);
        }

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

        boolean hasMore = trades.length >= limit;
        String nextCursor = hasMore ? String.valueOf(page + 1) : null;

        return new TradePageResult(items, hasMore, nextCursor);
    }

    @Override
    public List<String> getMarkets(ExchangeApiKey key) {
        BithumbMarket[] markets = fetchWithRetry(() -> {
            rateLimiter.waitForPermit(ExchangeContext.of(Exchange.BITHUMB), 1);
            return restClientFactory.create(key)
                    .get()
                    .uri(BASE_URL + "/v1/market/all")
                    .retrieve()
                    .body(BithumbMarket[].class);
        });

        if (markets == null) return List.of();

        return Arrays.stream(markets)
                .map(BithumbMarket::market)
                .filter(m -> m.startsWith("KRW-"))
                .map(m -> m.substring(4))
                .toList();
    }

    @Override
    public VerifyResult verify(ExchangeApiKey key) {
        try {
            rateLimiter.waitForPermit(ExchangeContext.of(Exchange.BITHUMB), 1);
            restClientFactory.create(key)
                    .get()
                    .uri(BASE_URL + "/v1/accounts")
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
     * 5xx: 2초 간격 3회
     * 401: 재시도 없음, 즉시 예외
     * Timeout: 5초 간격 3회
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
                    log.warn("빗썸 429 응답, {}초 후 재시도 ({}/{})", backoff / 1000, attempt429, maxRetries429);
                    sleep(backoff);
                } else {
                    throw e;
                }
            } catch (HttpServerErrorException e) {
                attempt5xx++;
                if (attempt5xx > maxRetries5xx) {
                    throw e;
                }
                log.warn("빗썸 5xx 응답, 2초 후 재시도 ({}/{})", attempt5xx, maxRetries5xx);
                sleep(2000);
            } catch (Exception e) {
                if (isTimeout(e)) {
                    attemptTimeout++;
                    if (attemptTimeout > maxRetriesTimeout) {
                        throw new RuntimeException("빗썸 API 타임아웃 초과", e);
                    }
                    log.warn("빗썸 타임아웃, 5초 후 재시도 ({}/{})", attemptTimeout, maxRetriesTimeout);
                    sleep(5000);
                } else {
                    throw e;
                }
            }
        }
    }

    /**
     * 빗썸 v2 JWT 토큰 생성.
     * 업비트와 동일하게 HS256 서명 + SHA-512 쿼리 해시.
     * 차이점: timestamp 클레임 필수 (Unix 밀리초).
     */
    private String generateToken(ExchangeApiKey key, String queryString) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("access_key", key.getAccessKey());
        claims.put("nonce", UUID.randomUUID().toString());
        claims.put("timestamp", System.currentTimeMillis());

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
     * 빗썸 /v1/orders 쿼리 파라미터 생성.
     * 페이지 기반 페이지네이션: page + limit.
     * order_by=desc (최신순) → 증분 수집 시 새 거래만 가져오고 조기 중단 가능.
     * states[]=done&states[]=cancel로 체결 완료 + 취소 주문 모두 조회.
     */
    private String buildTradeQueryString(String market, int page, int limit) {
        return "market=" + market
                + "&states[]=done&states[]=cancel"
                + "&page=" + page
                + "&limit=" + limit
                + "&order_by=desc";
    }

    private LocalDateTime parseDateTime(String dateTimeStr) {
        try {
            OffsetDateTime odt = OffsetDateTime.parse(dateTimeStr, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            return odt.toLocalDateTime();
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.EXCHANGE_API_RESPONSE_PARSE_FAILED);
        }
    }

    private boolean isTimeout(Exception e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof SocketTimeoutException
                    || cause instanceof TimeoutException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
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
