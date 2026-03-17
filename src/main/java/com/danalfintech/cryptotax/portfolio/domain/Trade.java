package com.danalfintech.cryptotax.portfolio.domain;

import com.danalfintech.cryptotax.exchange.common.Exchange;
import com.danalfintech.cryptotax.global.common.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "trades",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_trades_idempotent",
                columnNames = {"user_id", "exchange", "exchange_trade_id"}),
        indexes = {
                @Index(name = "idx_trades_user_exchange", columnList = "user_id, exchange"),
                @Index(name = "idx_trades_user_symbol", columnList = "user_id, exchange, symbol")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Trade extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Exchange exchange;

    @Column(nullable = false)
    private String exchangeTradeId;

    @Column(nullable = false, length = 30)
    private String symbol;

    @Column(nullable = false, length = 10)
    private String side;

    @Column(nullable = false, precision = 30, scale = 15)
    private BigDecimal price;

    @Column(nullable = false, precision = 30, scale = 15)
    private BigDecimal quantity;

    @Column(precision = 30, scale = 15)
    private BigDecimal fee;

    @Column(length = 10)
    private String feeCurrency;

    @Column(nullable = false)
    private LocalDateTime tradedAt;

    @Column(length = 30)
    private String market;

    @Builder
    public Trade(Long userId, Exchange exchange, String exchangeTradeId, String symbol, String side,
                 BigDecimal price, BigDecimal quantity, BigDecimal fee, String feeCurrency,
                 LocalDateTime tradedAt, String market) {
        this.userId = userId;
        this.exchange = exchange;
        this.exchangeTradeId = exchangeTradeId;
        this.symbol = symbol;
        this.side = side;
        this.price = price;
        this.quantity = quantity;
        this.fee = fee;
        this.feeCurrency = feeCurrency;
        this.tradedAt = tradedAt;
        this.market = market;
    }
}