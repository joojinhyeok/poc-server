package com.danalfintech.cryptotax.collection.worker;

import com.danalfintech.cryptotax.collection.domain.CollectionJob;
import com.danalfintech.cryptotax.collection.domain.CollectionJobRepository;
import com.danalfintech.cryptotax.collection.domain.CollectionJobType;
import com.danalfintech.cryptotax.collection.dto.CollectionMessage;
import com.danalfintech.cryptotax.collection.service.CollectionProgressService;
import com.danalfintech.cryptotax.exchange.common.Exchange;
import com.danalfintech.cryptotax.exchange.common.ExchangeApiKey;
import com.danalfintech.cryptotax.exchange.common.ExchangeApiKeyRepository;
import com.danalfintech.cryptotax.global.infra.exchange.ExchangeCollector;
import com.danalfintech.cryptotax.global.infra.exchange.dto.CollectionResult;
import com.danalfintech.cryptotax.global.infra.redis.ExchangeLeaseManager;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
public class CollectionProcessor {

    private final CollectionJobRepository collectionJobRepository;
    private final ExchangeApiKeyRepository exchangeApiKeyRepository;
    private final ExchangeLeaseManager leaseManager;
    private final CollectionProgressService progressService;
    private final RedisTemplate<String, String> redisTemplate;
    private final Map<Exchange, ExchangeCollector> collectorMap;
    private final Map<Exchange, Integer> maxConcurrentMap;

    public CollectionProcessor(
            CollectionJobRepository collectionJobRepository,
            ExchangeApiKeyRepository exchangeApiKeyRepository,
            ExchangeLeaseManager leaseManager,
            CollectionProgressService progressService,
            RedisTemplate<String, String> redisTemplate,
            List<ExchangeCollector> collectors,
            @Value("#{${app.exchange.max-concurrent}}") Map<String, Integer> maxConcurrent) {
        this.collectionJobRepository = collectionJobRepository;
        this.exchangeApiKeyRepository = exchangeApiKeyRepository;
        this.leaseManager = leaseManager;
        this.progressService = progressService;
        this.redisTemplate = redisTemplate;

        // ExchangeCollector를 Exchange별로 매핑
        this.collectorMap = new java.util.EnumMap<>(Exchange.class);
        // 런타임에 구현체가 여러 개 들어올 수 있으므로, 각 구현체의 클래스명에서 거래소를 추출
        // 또는 인터페이스에 getExchange() 메서드 추가 필요
        // 현재는 UpbitCollector만 존재하므로 리스트에서 직접 매핑
        for (ExchangeCollector collector : collectors) {
            String className = collector.getClass().getSimpleName().toUpperCase();
            for (Exchange exchange : Exchange.values()) {
                if (className.contains(exchange.name())) {
                    collectorMap.put(exchange, collector);
                }
            }
        }

        this.maxConcurrentMap = new java.util.EnumMap<>(Exchange.class);
        maxConcurrent.forEach((key, value) -> {
            try {
                this.maxConcurrentMap.put(Exchange.valueOf(key), value);
            } catch (IllegalArgumentException e) {
                log.warn("알 수 없는 거래소 설정: {}", key);
            }
        });
    }

    public void process(CollectionMessage message, Channel channel, long deliveryTag) {
        String workerId = UUID.randomUUID().toString();
        Exchange exchange = message.exchange();
        Long jobId = message.jobId();

        log.info("수집 메시지 수신: jobId={}, exchange={}, type={}", jobId, exchange, message.type());

        int maxConcurrent = maxConcurrentMap.getOrDefault(exchange, 3);

        // 1. Lease 획득
        if (!leaseManager.tryAcquire(exchange, workerId, maxConcurrent)) {
            log.info("Lease 획득 실패, 재큐잉: jobId={}, exchange={}", jobId, exchange);
            nack(channel, deliveryTag, true);
            return;
        }

        // 2. Heartbeat 시작 (Virtual Thread)
        Thread heartbeatThread = Thread.ofVirtual().start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(Duration.ofSeconds(30));
                    leaseManager.heartbeat(exchange, workerId);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });

        try {
            processCollection(message, workerId);
            ack(channel, deliveryTag);
        } catch (Exception e) {
            log.error("수집 처리 실패: jobId={}, exchange={}", jobId, exchange, e);
            handleFailure(jobId, e.getMessage());
            nack(channel, deliveryTag, false);
        } finally {
            heartbeatThread.interrupt();
            leaseManager.release(exchange, workerId);
        }
    }

    @Transactional
    protected void processCollection(CollectionMessage message, String workerId) {
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

    private void invalidateCaches(Long userId, Exchange exchange) {
        try {
            redisTemplate.delete("balance:" + userId + ":" + exchange.name());
            redisTemplate.delete("portfolio:summary:" + userId);
            redisTemplate.delete("tax:result:" + userId);
        } catch (Exception e) {
            log.warn("캐시 무효화 실패: userId={}, exchange={}", userId, exchange, e);
        }
    }

    private void handleFailure(Long jobId, String reason) {
        try {
            CollectionJob job = collectionJobRepository.findById(jobId).orElse(null);
            if (job != null) {
                job.markFailed(reason);
                collectionJobRepository.save(job);
                progressService.updateProgress(jobId, "FAILED", 0, 0, 0);
            }
        } catch (Exception e) {
            log.error("실패 처리 중 오류: jobId={}", jobId, e);
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