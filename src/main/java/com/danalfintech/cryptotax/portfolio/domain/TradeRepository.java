package com.danalfintech.cryptotax.portfolio.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public interface TradeRepository extends JpaRepository<Trade, Long> {

    @Modifying
    @Query(value = """
            INSERT INTO trades (user_id, exchange, exchange_trade_id, symbol, side, price, quantity, fee, fee_currency, traded_at, market, created_at, updated_at)
            VALUES (:userId, :exchange, :exchangeTradeId, :symbol, :side, :price, :quantity, :fee, :feeCurrency, :tradedAt, :market, NOW(), NOW())
            ON CONFLICT (user_id, exchange, exchange_trade_id) DO NOTHING
            """, nativeQuery = true)
    int insertIgnore(Long userId, String exchange, String exchangeTradeId, String symbol, String side,
                     BigDecimal price, BigDecimal quantity, BigDecimal fee, String feeCurrency,
                     LocalDateTime tradedAt, String market);
}