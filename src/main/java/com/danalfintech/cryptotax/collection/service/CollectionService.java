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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class CollectionService {

    private final CollectionJobRepository collectionJobRepository;
    private final ExchangeApiKeyRepository exchangeApiKeyRepository;
    private final RabbitTemplate rabbitTemplate;
    private final CollectionProgressService progressService;

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
        Optional<CollectionJob> existingJob = collectionJobRepository.findByUserIdAndExchangeAndStatusIn(
                userId, exchange, List.of(CollectionJobStatus.PENDING, CollectionJobStatus.PROCESSING));

        if (existingJob.isPresent()) {
            return CollectionStatusResponse.from(existingJob.get());
        }

        // 새 Job 생성
        CollectionJob job = CollectionJob.builder()
                .userId(userId)
                .exchange(exchange)
                .type(type)
                .build();
        collectionJobRepository.save(job);

        // Redis progress 초기화
        progressService.initProgress(job.getId());

        // RabbitMQ 발행
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

        try {
            rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE_COLLECTION, routingKey, message);
            log.info("수집 메시지 발행: jobId={}, exchange={}, type={}, routingKey={}",
                    job.getId(), exchange, type, routingKey);
        } catch (Exception e) {
            log.error("RabbitMQ 메시지 발행 실패: jobId={}", job.getId(), e);
            job.markFailed("메시지 발행 실패");
            throw new BusinessException(ErrorCode.COLLECTION_SERVICE_UNAVAILABLE);
        }

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