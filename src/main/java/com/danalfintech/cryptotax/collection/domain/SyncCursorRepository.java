package com.danalfintech.cryptotax.collection.domain;

import com.danalfintech.cryptotax.exchange.common.Exchange;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SyncCursorRepository extends JpaRepository<SyncCursor, Long> {

    Optional<SyncCursor> findByUserIdAndExchangeAndSymbol(Long userId, Exchange exchange, String symbol);

    List<SyncCursor> findAllByUserIdAndExchange(Long userId, Exchange exchange);
}