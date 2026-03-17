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
}