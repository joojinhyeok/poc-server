package com.danalfintech.cryptotax.collection.dto;

import com.danalfintech.cryptotax.collection.domain.CollectionJob;
import com.danalfintech.cryptotax.collection.domain.CollectionJobStatus;
import com.danalfintech.cryptotax.collection.domain.CollectionJobType;
import com.danalfintech.cryptotax.exchange.common.Exchange;

import java.time.LocalDateTime;

public record CollectionStatusResponse(
        Long jobId,
        Exchange exchange,
        CollectionJobType type,
        CollectionJobStatus status,
        int totalSymbols,
        int processedSymbols,
        int newTradesCount,
        String failReason,
        LocalDateTime startedAt,
        LocalDateTime completedAt
) {
    public static CollectionStatusResponse from(CollectionJob job) {
        return new CollectionStatusResponse(
                job.getId(),
                job.getExchange(),
                job.getType(),
                job.getStatus(),
                job.getTotalSymbols(),
                job.getProcessedSymbols(),
                job.getNewTradesCount(),
                job.getFailReason(),
                job.getStartedAt(),
                job.getCompletedAt()
        );
    }

    /**
     * Redis 실시간 진행률을 반영한 응답 생성.
     * 수집 진행 중(PROCESSING)일 때 DB보다 Redis가 더 최신 값을 가지므로 이를 우선 사용.
     */
    public static CollectionStatusResponse fromWithProgress(CollectionJob job, ProgressData progress) {
        return new CollectionStatusResponse(
                job.getId(),
                job.getExchange(),
                job.getType(),
                job.getStatus(),
                progress.totalSymbols(),
                progress.processedSymbols(),
                progress.newTradesCount(),
                job.getFailReason(),
                job.getStartedAt(),
                job.getCompletedAt()
        );
    }
}
