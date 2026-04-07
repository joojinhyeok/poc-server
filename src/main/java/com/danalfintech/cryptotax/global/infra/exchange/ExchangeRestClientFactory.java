package com.danalfintech.cryptotax.global.infra.exchange;

import com.danalfintech.cryptotax.exchange.common.entity.ExchangeApiKey;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 거래소 API 호출용 RestClient를 생성한다.
 *
 * v1 (현재): 기본 RestClient 반환.
 * v2 (IP 분산 도입 시): ExchangeApiKey.serverIp를 읽어
 *     해당 IP의 Forward Proxy를 경유하는 RestClient를 반환.
 *     이 메서드 내부만 수정하면 모든 Connector에 자동 적용됨.
 */
@Component
public class ExchangeRestClientFactory {

    private final RestClient.Builder restClientBuilder;

    public ExchangeRestClientFactory(RestClient.Builder restClientBuilder) {
        this.restClientBuilder = restClientBuilder;
    }

    public RestClient create(ExchangeApiKey key) {
        return restClientBuilder
                .defaultHeader("Content-Type", "application/json")
                .build();
    }
}
