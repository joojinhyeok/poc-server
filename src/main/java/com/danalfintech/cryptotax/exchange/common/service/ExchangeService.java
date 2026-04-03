package com.danalfintech.cryptotax.exchange.common.service;

import com.danalfintech.cryptotax.exchange.common.dto.ExchangeKeyRequest;
import com.danalfintech.cryptotax.exchange.common.dto.ExchangeKeyResponse;
import com.danalfintech.cryptotax.exchange.common.dto.ExchangeKeyUpdateRequest;
import com.danalfintech.cryptotax.exchange.common.dto.ExchangeVerifyResponse;
import com.danalfintech.cryptotax.exchange.common.entity.Exchange;
import com.danalfintech.cryptotax.exchange.common.entity.ExchangeApiKey;
import com.danalfintech.cryptotax.exchange.common.repository.ExchangeApiKeyRepository;
import com.danalfintech.cryptotax.global.error.BusinessException;
import com.danalfintech.cryptotax.global.error.ErrorCode;
import com.danalfintech.cryptotax.global.infra.exchange.ExchangeConnector;
import com.danalfintech.cryptotax.global.infra.exchange.dto.VerifyResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@Transactional(readOnly = true)
public class ExchangeService {

    private final ExchangeApiKeyRepository exchangeApiKeyRepository;
    private final Map<Exchange, ExchangeConnector> connectorMap;

    public ExchangeService(ExchangeApiKeyRepository exchangeApiKeyRepository,
                           List<ExchangeConnector> connectors) {
        this.exchangeApiKeyRepository = exchangeApiKeyRepository;
        this.connectorMap = connectors.stream()
                .collect(Collectors.toMap(ExchangeConnector::getExchange, Function.identity()));
    }

    @Transactional
    public ExchangeKeyResponse register(Long userId, ExchangeKeyRequest request) {
        if (exchangeApiKeyRepository.existsByUserIdAndExchange(userId, request.exchange())) {
            throw new BusinessException(ErrorCode.EXCHANGE_API_KEY_ALREADY_EXISTS);
        }

        ExchangeApiKey apiKey = ExchangeApiKey.builder()
                .userId(userId)
                .exchange(request.exchange())
                .accessKey(request.accessKey())
                .secretKey(request.secretKey())
                .memo(request.memo())
                .build();

        return ExchangeKeyResponse.from(exchangeApiKeyRepository.save(apiKey));
    }

    public List<ExchangeKeyResponse> getKeys(Long userId) {
        return exchangeApiKeyRepository.findAllByUserId(userId).stream()
                .map(ExchangeKeyResponse::from)
                .toList();
    }

    @Transactional
    public ExchangeKeyResponse update(Long userId, Long id, ExchangeKeyUpdateRequest request) {
        ExchangeApiKey apiKey = findByIdAndValidateOwner(id, userId);
        apiKey.update(request.accessKey(), request.secretKey(), request.memo());
        return ExchangeKeyResponse.from(apiKey);
    }

    @Transactional
    public void delete(Long userId, Long id) {
        ExchangeApiKey apiKey = findByIdAndValidateOwner(id, userId);
        exchangeApiKeyRepository.delete(apiKey);
    }

    @Transactional
    public ExchangeVerifyResponse verify(Long userId, Long id) {
        ExchangeApiKey apiKey = findByIdAndValidateOwner(id, userId);

        ExchangeConnector connector = connectorMap.get(apiKey.getExchange());
        if (connector == null) {
            return ExchangeVerifyResponse.of(false, "지원하지 않는 거래소입니다.");
        }

        VerifyResult result = connector.verify(apiKey);

        if (!result.valid()) {
            apiKey.invalidate();
        }

        return ExchangeVerifyResponse.of(result.valid(), result.message());
    }

    private ExchangeApiKey findByIdAndValidateOwner(Long id, Long userId) {
        return exchangeApiKeyRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EXCHANGE_API_KEY_NOT_FOUND));
    }
}
