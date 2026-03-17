package com.danalfintech.cryptotax.exchange.common;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ExchangeApiKeyRepository extends JpaRepository<ExchangeApiKey, Long> {

    Optional<ExchangeApiKey> findByUserIdAndExchange(Long userId, Exchange exchange);
}