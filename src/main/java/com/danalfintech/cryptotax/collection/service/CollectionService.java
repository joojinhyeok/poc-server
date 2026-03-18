package com.danalfintech.cryptotax.collection.service;

import com.danalfintech.cryptotax.collection.domain.*;
import com.danalfintech.cryptotax.collection.dto.CollectionMessage;
import com.danalfintech.cryptotax.collection.dto.CollectionStartRequest;
import com.danalfintech.cryptotax.collection.dto.CollectionStatusResponse;
import com.danalfintech.cryptotax.exchange.common.Exchange;
import com.danalfintech.cryptotax.exchange.common.ExchangeApiKey;
import com.danalfintech.cryptotax.exchange.common.ExchangeApiKeyRepository;
import com.danalfintech.cryptotax.global.config.RabbitConfig;
import com.danalfintech.cryptotax.global.error.BusinessException;
import com.danalfintech.cryptotax.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class CollectionService {

    private final CollectionJobRepository collectionJobRepository;
    private final ExchangeApiKeyRepository exchangeApiKeyRepository;
    private final RabbitTemplate rabbitTemplate;
    private final CollectionProgressService progressService;
    private final TransactionTemplate transactionTemplate;

    public CollectionService(
            CollectionJobRepository collectionJobRepository,
            ExchangeApiKeyRepository exchangeApiKeyRepository,
            RabbitTemplate rabbitTemplate,
            CollectionProgressService progressService,
            PlatformTransactionManager transactionManager) {
        this.collectionJobRepository = collectionJobRepository;
        this.exchangeApiKeyRepository = exchangeApiKeyRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.progressService = progressService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Transactional
    public CollectionStatusResponse startCollection(Long userId, CollectionStartRequest request) {
        Exchange exchange = request.exchange();
        CollectionJobType type = request.type();

        // API Key 확인
        ExchangeApiKey apiKey = exchangeApiKeyRepository.findByUserIdAndExchange(userId, exchange)
                .orElseThrow(() -> new BusinessException(ErrorCode.EXCHANGE_API_KEY_NOT_FOUND));

        if (!apiKey.isValid()) {
            throw new BusinessException(ErrorCode.EXCHANGE_API_KEY_INVALID);
        }

        // Dedupe 체크: 같은 user + exchange + PENDING/PROCESSING이면 기존 반환
        Optional<CollectionJob> existingJob = collectionJobRepository
                .findByUserIdAndExchangeAndStatusIn(
                        userId, exchange, List.of(CollectionJobStatus.PENDING, CollectionJobStatus.PROCESSING));

        if (existingJob.isPresent()) {
            return CollectionStatusResponse.from(existingJob.get());
        }

        // 새 Job 생성 — partial unique index(uk_cj_active_per_user_exchange)가 중복 방지
        CollectionJob job;
        try {
            job = CollectionJob.builder()
                    .userId(userId)
                    .exchange(exchange)
                    .type(type)
                    .build();
            collectionJobRepository.saveAndFlush(job);
        } catch (DataIntegrityViolationException e) {
            // 동시 요청으로 partial unique index 위반 → 기존 Job 반환
            log.info("Dedupe: 동시 요청으로 중복 감지, 기존 Job 반환: userId={}, exchange={}", userId, exchange);
            return CollectionStatusResponse.from(
                    collectionJobRepository.findByUserIdAndExchangeAndStatusIn(
                            userId, exchange, List.of(CollectionJobStatus.PENDING, CollectionJobStatus.PROCESSING))
                            .orElseThrow(() -> new BusinessException(ErrorCode.COLLECTION_JOB_NOT_FOUND)));
        }

        // Redis progress 초기화
        progressService.initProgress(job.getId());

        // RabbitMQ 발행: DB 커밋 후 발행 (AFTER_COMMIT)
        String routingKey = (type == CollectionJobType.INCREMENTAL)
                ? RabbitConfig.ROUTING_HIGH
                : RabbitConfig.ROUTING_LOW;

        CollectionMessage message = new CollectionMessage(
                job.getId(),
                userId,
                exchange,
                apiKey.getId(),
                type,
                LocalDateTime.now()
        );

        Long jobId = job.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE_COLLECTION, routingKey, message);
                    log.info("수집 메시지 발행: jobId={}, exchange={}, type={}, routingKey={}",
                            jobId, exchange, type, routingKey);
                } catch (Exception e) {
                    log.error("RabbitMQ 메시지 발행 실패: jobId={}", jobId, e);
                    // 별도 트랜잭션으로 Job을 FAILED 처리 → 사용자 재요청 가능
                    try {
                        transactionTemplate.executeWithoutResult(status ->
                                collectionJobRepository.findById(jobId).ifPresent(j -> {
                                    j.markFailed("메시지 큐 발행 실패");
                                    collectionJobRepository.save(j);
                                }));
                    } catch (Exception ex) {
                        log.error("Job FAILED 처리도 실패: jobId={}", jobId, ex);
                    }
                }
            }
        });

        return CollectionStatusResponse.from(job);
    }

    @Transactional(readOnly = true)
    public List<CollectionStatusResponse> getActiveJobs(Long userId) {
        List<CollectionJob> jobs = collectionJobRepository.findAllByUserIdAndStatusIn(
                userId, List.of(CollectionJobStatus.PENDING, CollectionJobStatus.PROCESSING));
        return jobs.stream()
                .map(CollectionStatusResponse::from)
                .toList();
    }
}
