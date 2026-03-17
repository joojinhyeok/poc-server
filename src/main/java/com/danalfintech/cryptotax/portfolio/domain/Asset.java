package com.danalfintech.cryptotax.portfolio.domain;

import com.danalfintech.cryptotax.exchange.common.Exchange;
import com.danalfintech.cryptotax.global.common.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Entity
@Table(name = "assets",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_assets_upsert",
                columnNames = {"user_id", "exchange", "coin"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Asset extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Exchange exchange;

    @Column(nullable = false, length = 20)
    private String coin;

    @Column(nullable = false, precision = 30, scale = 15)
    private BigDecimal amount;

    @Column(precision = 30, scale = 15)
    private BigDecimal avgBuyPrice;

    @Column(precision = 30, scale = 15)
    private BigDecimal lockedAmount;

    @Builder
    public Asset(Long userId, Exchange exchange, String coin, BigDecimal amount,
                 BigDecimal avgBuyPrice, BigDecimal lockedAmount) {
        this.userId = userId;
        this.exchange = exchange;
        this.coin = coin;
        this.amount = amount;
        this.avgBuyPrice = avgBuyPrice;
        this.lockedAmount = lockedAmount;
    }

    public void updateBalance(BigDecimal amount, BigDecimal avgBuyPrice, BigDecimal lockedAmount) {
        this.amount = amount;
        this.avgBuyPrice = avgBuyPrice;
        this.lockedAmount = lockedAmount;
    }
}