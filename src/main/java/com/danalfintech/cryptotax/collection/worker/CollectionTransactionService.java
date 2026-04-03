package com.danalfintech.cryptotax.collection.worker;

import com.danalfintech.cryptotax.collection.domain.CollectionJob;
import com.danalfintech.cryptotax.collection.domain.CollectionJobRepository;
import com.danalfintech.cryptotax.collection.dto.CollectionMessage;
import com.danalfintech.cryptotax.collection.service.CollectionProgressService;
import com.danalfintech.cryptotax.exchange.common.entity.Exchange;
import com.danalfintech.cryptotax.global.common.RedisKeyBuilder;
import com.danalfintech.cryptotax.global.infra.exchange.ExchangeContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class CollectionTransactionService {

    private final CollectionJobRepository collectionJobRepository;
    private final CollectionProgressService progressService;
    private final RedisTemplate<String, String> redisTemplate;
    private final CollectionFacade collectionFacade;

    public CollectionTransactionService(
            CollectionJobRepository collectionJobRepository,
            CollectionProgressService progressService,
            RedisTemplate<String, String> redisTemplate,
            CollectionFacade collectionFacade) {
        this.collectionJobRepository = collectionJobRepository;
        this.progressService = progressService;
        this.redisTemplate = redisTemplate;
        this.collectionFacade = collectionFacade;
    }

    /**
     * 기존 CollectionMessage를 받아 Facade로 위임한다.
     * Facade가 심볼 단위로 쪼개서 MQ에 재발행하므로 이 메서드는 트랜잭션 불필요.
     */
    public void processCollection(CollectionMessage message) {
        collectionFacade.dispatch(message);
    }

    @Transactional
    public void handleFailure(Long jobId, String reason) {
        CollectionJob job = collectionJobRepository.findById(jobId).orElse(null);
        if (job != null) {
            job.markFailed();
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
