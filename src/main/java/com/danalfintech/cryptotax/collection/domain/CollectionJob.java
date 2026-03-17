package com.danalfintech.cryptotax.collection.domain;

import com.danalfintech.cryptotax.exchange.common.Exchange;
import com.danalfintech.cryptotax.global.common.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "collection_jobs",
        indexes = {
                @Index(name = "idx_cj_user_exchange_status",
                        columnList = "user_id, exchange, status")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CollectionJob extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Exchange exchange;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CollectionJobType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CollectionJobStatus status;

    private int totalSymbols;
    private int processedSymbols;
    private int newTradesCount;
    private String failReason;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;

    @Builder
    public CollectionJob(Long userId, Exchange exchange, CollectionJobType type) {
        this.userId = userId;
        this.exchange = exchange;
        this.type = type;
        this.status = CollectionJobStatus.PENDING;
    }

    public void markProcessing() {
        this.status = CollectionJobStatus.PROCESSING;
        this.startedAt = LocalDateTime.now();
    }

    public void markCompleted(int totalSymbols, int processedSymbols, int newTradesCount) {
        this.status = CollectionJobStatus.COMPLETED;
        this.totalSymbols = totalSymbols;
        this.processedSymbols = processedSymbols;
        this.newTradesCount = newTradesCount;
        this.completedAt = LocalDateTime.now();
    }

    public void markPartial(int totalSymbols, int processedSymbols, int newTradesCount, String failReason) {
        this.status = CollectionJobStatus.PARTIAL;
        this.totalSymbols = totalSymbols;
        this.processedSymbols = processedSymbols;
        this.newTradesCount = newTradesCount;
        this.failReason = failReason;
        this.completedAt = LocalDateTime.now();
    }

    public void markFailed(String failReason) {
        this.status = CollectionJobStatus.FAILED;
        this.failReason = failReason;
        this.completedAt = LocalDateTime.now();
    }

    public void updateProgress(int processedSymbols, int newTradesCount) {
        this.processedSymbols = processedSymbols;
        this.newTradesCount = newTradesCount;
    }

    public void setTotalSymbols(int totalSymbols) {
        this.totalSymbols = totalSymbols;
    }
}