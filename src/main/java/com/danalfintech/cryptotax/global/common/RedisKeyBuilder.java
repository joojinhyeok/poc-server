package com.danalfintech.cryptotax.global.common;

import com.danalfintech.cryptotax.global.infra.exchange.ExchangeContext;

/**
 * 모든 Redis 키를 이 클래스에서 생성한다.
 * 코드 내에서 Redis 키 문자열을 직접 조합하지 않는다.
 *
 * v1 (현재): serverIp가 null이면 기존 키 포맷 유지.
 * v2 (IP 분산 도입 시): serverIp가 있으면 IP별 독립 키 생성.
 *     이 클래스 내부만 수정하면 모든 사용처에 자동 적용됨.
 *
 * 마이그레이션 참고: IP 분산 도입 시 Rate Limit/Lease 키 포맷이 바뀜.
 * 이 키들은 TTL이 짧아(1~120초) 자연 만료됨.
 * 캐시 키(balance, portfolio, tax)는 배포 시 FLUSHDB 권장.
 */
public final class RedisKeyBuilder {

    private RedisKeyBuilder() {}

    private static String ipSegment(ExchangeContext ctx) {
        return (ctx.serverIp() != null) ? ctx.serverIp() + ":" : "";
    }

    // === Rate Limiter ===
    public static String rateLimitFixedWindow(ExchangeContext ctx, long windowId) {
        return "ratelimit:fixed:" + ipSegment(ctx) + ctx.exchange().name() + ":" + windowId;
    }

    public static String rateLimitWeight(ExchangeContext ctx, long windowId) {
        return "ratelimit:weight:" + ipSegment(ctx) + ctx.exchange().name() + ":" + windowId;
    }

    // === Lease ===
    public static String leaseKey(ExchangeContext ctx) {
        return "exchange:leases:" + ipSegment(ctx) + ctx.exchange().name();
    }

    // === Collection Progress ===
    public static String progressKey(Long jobId) {
        return "collection:progress:" + jobId;
    }

    // === Cache Invalidation ===
    public static String balanceKey(Long userId, ExchangeContext ctx) {
        return "balance:" + userId + ":" + ctx.exchange().name();
    }

    public static String portfolioSummaryKey(Long userId) {
        return "portfolio:summary:" + userId;
    }

    public static String taxResultKey(Long userId) {
        return "tax:result:" + userId;
    }
}
