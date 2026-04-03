package com.danalfintech.cryptotax.exchange.common.entity;

import com.danalfintech.cryptotax.global.common.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "exchange_api_keys",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "exchange"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExchangeApiKey extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Exchange exchange;

    @Column(nullable = false)
    private String accessKey;

    @Column(nullable = false)
    private String secretKey;

    @Column(nullable = false)
    private boolean isValid = true;

    private String memo;

    @Builder
    public ExchangeApiKey(Long userId, Exchange exchange, String accessKey, String secretKey, String memo) {
        this.userId = userId;
        this.exchange = exchange;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.memo = memo;
    }

    public void invalidate() {
        this.isValid = false;
    }

    public void update(String accessKey, String secretKey, String memo) {
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.memo = memo;
        this.isValid = true;
    }
}