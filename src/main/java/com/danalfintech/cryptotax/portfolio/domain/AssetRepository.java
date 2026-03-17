package com.danalfintech.cryptotax.portfolio.domain;

import com.danalfintech.cryptotax.exchange.common.Exchange;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AssetRepository extends JpaRepository<Asset, Long> {

    Optional<Asset> findByUserIdAndExchangeAndCoin(Long userId, Exchange exchange, String coin);
}