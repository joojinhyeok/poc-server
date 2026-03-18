package com.danalfintech.cryptotax.collection.worker;

import com.danalfintech.cryptotax.collection.domain.CollectionJob;
import com.danalfintech.cryptotax.collection.domain.CollectionJobRepository;
import com.danalfintech.cryptotax.collection.domain.CollectionJobType;
import com.danalfintech.cryptotax.collection.dto.CollectionMessage;
import com.danalfintech.cryptotax.collection.service.CollectionProgressService;
import com.danalfintech.cryptotax.exchange.common.Exchange;
import com.danalfintech.cryptotax.exchange.common.ExchangeApiKey;
import com.danalfintech.cryptotax.exchange.common.ExchangeApiKeyRepository;
import com.danalfintech.cryptotax.global.common.RedisKeyBuilder;
import com.danalfintech.cryptotax.global.infra.exchange.ExchangeCollector;
import com.danalfintech.cryptotax.global.infra.exchange.ExchangeContext;
import com.danalfintech.cryptotax.global.infra.exchange.dto.CollectionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class CollectionTransactionService {

    private final CollectionJobRepository collectionJobRepository;
    private final ExchangeApiKeyRepository exchangeApiKeyRepository;
    private final CollectionProgressService progressService;
    private final RedisTemplate<String, String> redisTemplate;
    private final Map<Exchange, ExchangeCollector> collectorMap;

    public CollectionTransactionService(
            CollectionJobRepository collectionJobRepository,
            ExchangeApiKeyRepository exchangeApiKeyRepository,
            CollectionProgressService progressService,
            RedisTemplate<String, String> redisTemplate,
            List<ExchangeCollector> collectors) {
        this.collectionJobRepository = collectionJobRepository;
        this.exchangeApiKeyRepository = exchangeApiKeyRepository;
        this.progressService = progressService;
        this.redisTemplate = redisTemplate;

        this.collectorMap = new EnumMap<>(Exchange.class);
        for (ExchangeCollector collector : collectors) {
            collectorMap.put(collector.getExchange(), collector);
        }
    }

    @Transactional
    public void processCollection(CollectionMessage message) {
        Long jobId = message.jobId();
        Exchange exchange = message.exchange();

        CollectionJob job = collectionJobRepository.findById(jobId)
                .orElseThrow(() -> new RuntimeException("수집 작업을 찾을 수 없음: " + jobId));

        // 상태 전이: PENDING → PROCESSING
        job.markProcessing();
        collectionJobRepository.save(job);

        // API Key 조회
        ExchangeApiKey apiKey = exchangeApiKeyRepository.findById(message.apiKeyId())
                .orElseThrow(() -> new RuntimeException("API Key를 찾을 수 없음: " + message.apiKeyId()));

        // Collector 조회
        ExchangeCollector collector = collectorMap.get(exchange);
        if (collector == null) {
            throw new RuntimeException("지원하지 않는 거래소: " + exchange);
        }

        // 수집 실행
        CollectionResult result;
        if (message.type() == CollectionJobType.FULL) {
            result = collector.collectAll(job, apiKey);
        } else {
            result = collector.collectIncremental(job, apiKey);
        }

        // 결과 반영
        switch (result.finalStatus()) {
            case COMPLETED -> job.markCompleted(result.totalSymbols(), result.processedSymbols(), result.newTradesCount());
            case PARTIAL -> job.markPartial(result.totalSymbols(), result.processedSymbols(), result.newTradesCount(), result.failReason());
            case FAILED -> job.markFailed(result.failReason());
            default -> job.markCompleted(result.totalSymbols(), result.processedSymbols(), result.newTradesCount());
        }
        collectionJobRepository.save(job);

        // 진행률 업데이트
        progressService.updateProgress(jobId, job.getStatus().name(),
                result.totalSymbols(), result.processedSymbols(), result.newTradesCount());

        // 캐시 무효화 (보충 9: DELETE만)
        invalidateCaches(message.userId(), exchange);

        log.info("수집 완료: jobId={}, status={}, trades={}", jobId, result.finalStatus(), result.newTradesCount());
    }

    @Transactional
    public void handleFailure(Long jobId, String reason) {
        CollectionJob job = collectionJobRepository.findById(jobId).orElse(null);
        if (job != null) {
            job.markFailed(reason);
            collectionJobRepository.save(job);
            progressService.updateProgress(jobId, "FAILED", 0, 0, 0);
        }
    }

    private void invalidateCaches(Long userId, Exchange exchange) {
        try {
            ExchangeContext ctx = ExchangeContext.of(exchange);
            redisTemplate.delete(RedisKeyBuilder.balanceKey(userId, ctx));
            redisTemplate.delete(RedisKeyBuilder.portfolioSummaryKey(userId));
            redisTemplate.delete(RedisKeyBuilder.taxResultKey(userId));
        } catch (Exception e) {
            log.warn("캐시 무효화 실패: userId={}, exchange={}", userId, exchange, e);
        }
    }
}
