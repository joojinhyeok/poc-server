package com.danalfintech.cryptotax.collection.domain;

import com.danalfintech.cryptotax.exchange.common.entity.Exchange;
import com.danalfintech.cryptotax.global.common.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "sync_cursors",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "exchange", "symbol"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SyncCursor extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Exchange exchange;

    @Column(nullable = false, length = 30)
    private String symbol;

    @Column(nullable = false)
    private String lastTradeId;

    @Column(nullable = false)
    private LocalDateTime lastSyncedAt;

    @Builder
    public SyncCursor(Long userId, Exchange exchange, String symbol, String lastTradeId, LocalDateTime lastSyncedAt) {
        this.userId = userId;
        this.exchange = exchange;
        this.symbol = symbol;
        this.lastTradeId = lastTradeId;
        this.lastSyncedAt = lastSyncedAt;
    }

    public void update(String lastTradeId, LocalDateTime lastSyncedAt) {
        this.lastTradeId = lastTradeId;
        this.lastSyncedAt = lastSyncedAt;
    }
}