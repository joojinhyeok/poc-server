package com.danalfintech.cryptotax.collection.worker;

import com.danalfintech.cryptotax.collection.domain.*;
import com.danalfintech.cryptotax.collection.dto.SymbolCollectionMessage;
import com.danalfintech.cryptotax.collection.service.CollectionProgressService;
import com.danalfintech.cryptotax.exchange.common.entity.Exchange;
import com.danalfintech.cryptotax.exchange.common.entity.ExchangeApiKey;
import com.danalfintech.cryptotax.exchange.common.repository.ExchangeApiKeyRepository;
import com.danalfintech.cryptotax.global.config.RabbitConfig;
import com.danalfintech.cryptotax.global.infra.exchange.ExchangeCollector;
import com.danalfintech.cryptotax.global.infra.exchange.dto.SymbolCollectionResult;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class SymbolCollectionWorker {

    private final ExchangeApiKeyRepository exchangeApiKeyRepository;
    private final CollectionJobRepository collectionJobRepository;
    private final CollectionFailureRepository collectionFailureRepository;
    private final SyncCursorRepository syncCursorRepository;
    private final CollectionProgressService progressService;
    private final Map<Exchange, ExchangeCollector> collectorMap;

    public SymbolCollectionWorker(
            ExchangeApiKeyRepository exchangeApiKeyRepository,
            CollectionJobRepository collectionJobRepository,
            CollectionFailureRepository collectionFailureRepository,
            SyncCursorRepository syncCursorRepository,
            CollectionProgressService progressService,
            List<ExchangeCollector> collectors) {
        this.exchangeApiKeyRepository = exchangeApiKeyRepository;
        this.collectionJobRepository = collectionJobRepository;
        this.collectionFailureRepository = collectionFailureRepository;
        this.syncCursorRepository = syncCursorRepository;
        this.progressService = progressService;

        this.collectorMap = new EnumMap<>(Exchange.class);
        for (ExchangeCollector collector : collectors) {
            collectorMap.put(collector.getExchange(), collector);
        }
    }

    @RabbitListener(
            queues = RabbitConfig.QUEUE_SYMBOL,
            concurrency = "10",
            ackMode = "MANUAL"
    )
    public void onMessage(SymbolCollectionMessage message, Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        Long jobId = message.jobId();
        String symbol = message.symbol();

        log.info("심볼 수집 시작: jobId={}, symbol={}", jobId, symbol);

        try {
            // 1. API Key 조회
            ExchangeApiKey apiKey = exchangeApiKeyRepository.findById(message.apiKeyId())
                    .orElseThrow(() -> new RuntimeException("API Key를 찾을 수 없음: " + message.apiKeyId()));

            // 2. Collector 조회
            ExchangeCollector collector = collectorMap.get(message.exchange());
            if (collector == null) {
                throw new RuntimeException("지원하지 않는 거래소: " + message.exchange());
            }

            // 3. 증분 수집 시 커서 조회
            String fromCursor = null;
            if (message.type() == CollectionJobType.INCREMENTAL) {
                fromCursor = syncCursorRepository
                        .findByUserIdAndExchangeAndSymbol(message.userId(), message.exchange(), symbol)
                        .map(SyncCursor::getLastTradeId)
                        .orElse(null);
            }

            // 4. 심볼 수집 실행 (독립 트랜잭션)
            SymbolCollectionResult result = collector.collectSymbol(
                    message.userId(), apiKey, symbol, fromCursor);

            // 5. 원자적 진행률 업데이트 + 최종 상태 판정
            boolean jobDone;
            if (result.success()) {
                jobDone = handleSymbolSuccess(jobId, result.newTradesCount(), message.totalSymbols());
                log.info("심볼 수집 완료: jobId={}, symbol={}, trades={}", jobId, symbol, result.newTradesCount());
            } else {
                jobDone = handleSymbolFailure(jobId, symbol, result.failReason(), message.totalSymbols());
                log.warn("심볼 수집 실패: jobId={}, symbol={}, reason={}", jobId, symbol, result.failReason());
            }

            // 6. 모든 심볼 처리 완료 → 잔고 동기화 (외부 API 호출이므로 트랜잭션 밖에서)
            if (jobDone) {
                syncBalancesAfterCompletion(collector, message.userId(), apiKey, jobId);
            }

            ack(channel, deliveryTag);

        } catch (Exception e) {
            log.error("심볼 수집 처리 실패: jobId={}, symbol={}", jobId, symbol, e);
            nack(channel, deliveryTag, false);
        }
    }

    @Transactional
    protected boolean handleSymbolSuccess(Long jobId, int newTrades, int totalSymbols) {
        int processed = collectionJobRepository.incrementProgressAndGet(jobId, newTrades);
        return finalizeIfDone(jobId, processed, totalSymbols);
    }

    @Transactional
    protected boolean handleSymbolFailure(Long jobId, String symbol, String reason, int totalSymbols) {
        collectionFailureRepository.save(
                CollectionFailure.builder()
                        .jobId(jobId)
                        .symbol(symbol)
                        .reason(reason != null ? reason.substring(0, Math.min(reason.length(), 500)) : "unknown")
                        .build()
        );

        int processed = collectionJobRepository.incrementFailureAndGet(jobId);
        return finalizeIfDone(jobId, processed, totalSymbols);
    }

    /**
     * @return true if job is finalized (all symbols processed)
     */
    private boolean finalizeIfDone(Long jobId, int processed, int totalSymbols) {
        if (processed < totalSymbols) {
            progressService.updateProgress(jobId, "PROCESSING", totalSymbols, processed);
            return false;
        }

        CollectionJob job = collectionJobRepository.findById(jobId).orElse(null);
        if (job == null) return false;

        if (job.getFailedSymbols() == 0) {
            job.markCompleted();
        } else if (job.getFailedSymbols() < totalSymbols) {
            job.markPartial();
        } else {
            job.markFailed();
        }
        collectionJobRepository.save(job);

        progressService.updateProgress(jobId, job.getStatus().name(),
                totalSymbols, processed);
        return true;
    }

    private void syncBalancesAfterCompletion(ExchangeCollector collector, Long userId,
                                              ExchangeApiKey apiKey, Long jobId) {
        try {
            collector.syncBalances(userId, apiKey);
            log.info("잔고 동기화 완료: jobId={}, userId={}", jobId, userId);
        } catch (Exception e) {
            log.warn("잔고 동기화 실패 (수집 자체는 성공): jobId={}, userId={}", jobId, userId, e);
        }
    }

    private void ack(Channel channel, long deliveryTag) {
        try {
            channel.basicAck(deliveryTag, false);
        } catch (IOException e) {
            log.error("ACK 실패", e);
        }
    }

    private void nack(Channel channel, long deliveryTag, boolean requeue) {
        try {
            channel.basicNack(deliveryTag, false, requeue);
        } catch (IOException e) {
            log.error("NACK 실패", e);
        }
    }
}
