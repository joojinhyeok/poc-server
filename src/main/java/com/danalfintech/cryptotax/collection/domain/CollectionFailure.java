package com.danalfintech.cryptotax.collection.domain;

import com.danalfintech.cryptotax.global.common.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "collection_failures",
        indexes = @Index(name = "idx_cf_job_id", columnList = "job_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CollectionFailure extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", nullable = false)
    private Long jobId;

    @Column(nullable = false, length = 50)
    private String symbol;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(nullable = false)
    private boolean retried;

    @Builder
    public CollectionFailure(Long jobId, String symbol, String reason) {
        this.jobId = jobId;
        this.symbol = symbol;
        this.reason = reason;
        this.retried = false;
    }

    public void markRetried() {
        this.retried = true;
    }
}
