package com.danalfintech.cryptotax.collection.worker;

import com.danalfintech.cryptotax.collection.domain.CollectionJob;
import com.danalfintech.cryptotax.collection.domain.CollectionJobRepository;
import com.danalfintech.cryptotax.collection.dto.CollectionMessage;
import com.danalfintech.cryptotax.collection.dto.SymbolCollectionMessage;
import com.danalfintech.cryptotax.collection.service.CollectionProgressService;
import com.danalfintech.cryptotax.exchange.common.entity.Exchange;
import com.danalfintech.cryptotax.exchange.common.entity.ExchangeApiKey;
import com.danalfintech.cryptotax.exchange.common.repository.ExchangeApiKeyRepository;
import com.danalfintech.cryptotax.global.config.RabbitConfig;
import com.danalfintech.cryptotax.global.infra.exchange.ExchangeConnector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 수집 요청을 심볼 단위 메시지로 분해하여 MQ에 발행하는 퍼사드.
 *
 * 기존: 1 메시지 → 1 트랜잭션에서 전체 심볼 수집 (DB 커넥션 장시간 점유)
 * 개선: 1 메시지 → 심볼 목록 조회 → N개 심볼 메시지 발행 (각각 독립 트랜잭션)
 */
@Slf4j
@Component
public class CollectionFacade {

    private static final int MAX_PUBLISH_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 1000;

    private final CollectionJobRepository collectionJobRepository;
    private final ExchangeApiKeyRepository exchangeApiKeyRepository;
    private final CollectionProgressService progressService;
    private final RabbitTemplate rabbitTemplate;
    private final Map<Exchange, ExchangeConnector> connectorMap;

    public CollectionFacade(
            CollectionJobRepository collectionJobRepository,
            ExchangeApiKeyRepository exchangeApiKeyRepository,
            CollectionProgressService progressService,
            RabbitTemplate rabbitTemplate,
            List<ExchangeConnector> connectors) {
        this.collectionJobRepository = collectionJobRepository;
        this.exchangeApiKeyRepository = exchangeApiKeyRepository;
        this.progressService = progressService;
        this.rabbitTemplate = rabbitTemplate;

        this.connectorMap = new EnumMap<>(Exchange.class);
        for (ExchangeConnector connector : connectors) {
            connectorMap.put(connector.getExchange(), connector);
        }
    }

    /**
     * Job 메시지를 받아 심볼별 메시지로 쪼개서 MQ에 발행한다.
     * 이 메서드 자체는 트랜잭션 없이 동작한다 (외부 API 호출 포함)
     */
    public void dispatch(CollectionMessage message) {
        Long jobId = message.jobId();
        Exchange exchange = message.exchange();

        // 1. Job 상태 전이: PENDING → PROCESSING
        markJobProcessing(jobId);

        // 2. API Key 조회
        ExchangeApiKey apiKey = exchangeApiKeyRepository.findById(message.apiKeyId())
                .orElseThrow(() -> new RuntimeException("API Key를 찾을 수 없음: " + message.apiKeyId()));

        // 3. Connector로 심볼 목록 조회 (외부 API 호출 — 트랜잭션 밖)
        ExchangeConnector connector = connectorMap.get(exchange);
        if (connector == null) {
            handleDispatchFailure(jobId, "지원하지 않는 거래소: " + exchange);
            return;
        }

        List<String> symbols;
        try {
            symbols = connector.getMarkets(apiKey);
        } catch (Exception e) {
            handleDispatchFailure(jobId, "마켓 목록 조회 실패: " + e.getMessage());
            return;
        }

        if (symbols.isEmpty()) {
            handleDispatchFailure(jobId, "수집할 마켓이 없습니다.");
            return;
        }

        // 4. Job에 totalSymbols 기록
        updateJobTotalSymbols(jobId, symbols.size());

        // 5. 심볼별 메시지 발행
        int publishedCount = 0;
        StringBuilder failedSymbols = new StringBuilder();

        for (String symbol : symbols) {
            SymbolCollectionMessage symbolMessage = new SymbolCollectionMessage(
                    jobId,
                    message.userId(),
                    exchange,
                    message.apiKeyId(),
                    message.type(),
                    symbol,
                    symbols.size()
            );

            if (publishWithRetry(symbolMessage)) {
                publishedCount++;
            } else {
                failedSymbols.append(symbol).append(", ");
                log.error("심볼 메시지 발행 최종 실패: jobId={}, symbol={}", jobId, symbol);
            }
        }

        if (publishedCount == 0) {
            handleDispatchFailure(jobId, "모든 심볼 메시지 발행 실패: " + failedSymbols);
        } else if (publishedCount < symbols.size()) {
            log.warn("일부 심볼 메시지 발행 실패: jobId={}, 성공={}/{}, 실패={}",
                    jobId, publishedCount, symbols.size(), failedSymbols);
        } else {
            log.info("심볼 메시지 전체 발행 완료: jobId={}, symbols={}", jobId, symbols.size());
        }
    }

    private boolean publishWithRetry(SymbolCollectionMessage message) {
        for (int attempt = 1; attempt <= MAX_PUBLISH_RETRIES; attempt++) {
            try {
                rabbitTemplate.convertAndSend(
                        RabbitConfig.EXCHANGE_COLLECTION,
                        RabbitConfig.ROUTING_SYMBOL,
                        message
                );
                return true;
            } catch (Exception e) {
                log.warn("심볼 메시지 발행 실패 (시도 {}/{}): jobId={}, symbol={}, error={}",
                        attempt, MAX_PUBLISH_RETRIES, message.jobId(), message.symbol(), e.getMessage());
                if (attempt < MAX_PUBLISH_RETRIES) {
                    sleep(RETRY_DELAY_MS * attempt);
                }
            }
        }
        return false;
    }

    @Transactional
    protected void markJobProcessing(Long jobId) {
        CollectionJob job = collectionJobRepository.findById(jobId)
                .orElseThrow(() -> new RuntimeException("수집 작업을 찾을 수 없음: " + jobId));
        job.markProcessing();
        collectionJobRepository.save(job);
    }

    @Transactional
    protected void updateJobTotalSymbols(Long jobId, int totalSymbols) {
        CollectionJob job = collectionJobRepository.findById(jobId)
                .orElseThrow(() -> new RuntimeException("수집 작업을 찾을 수 없음: " + jobId));
        job.setTotalSymbols(totalSymbols);
        collectionJobRepository.save(job);
        progressService.updateProgress(jobId, "PROCESSING", totalSymbols, 0, 0);
    }

    @Transactional
    protected void handleDispatchFailure(Long jobId, String reason) {
        log.error("수집 디스패치 실패: jobId={}, reason={}", jobId, reason);
        collectionJobRepository.findById(jobId).ifPresent(job -> {
            job.markFailed();
            collectionJobRepository.save(job);
        });
        progressService.updateProgress(jobId, "FAILED", 0, 0, 0);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
