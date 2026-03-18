package com.danalfintech.cryptotax.global.infra.exchange;

import com.danalfintech.cryptotax.exchange.common.Exchange;

/**
 * 거래소 API 호출의 라우팅 컨텍스트.
 * Rate Limiter, Lease Manager, Redis 키 생성 등에서 공통으로 사용한다.
 *
 * v1 (현재): serverIp는 null. 단일 서버 IP로 운영.
 * v2 (IP 분산 도입 시): ExchangeApiKey.serverIp를 읽어 생성.
 *     이 객체를 받는 모든 계층이 자동으로 IP별 분리됨.
 */
public record ExchangeContext(
        Exchange exchange,
        String serverIp
) {
    public static ExchangeContext of(Exchange exchange) {
        return new ExchangeContext(exchange, null);
    }

    public static ExchangeContext of(Exchange exchange, String serverIp) {
        return new ExchangeContext(exchange, serverIp);
    }
}
