package com.danalfintech.cryptotax.exchange.common.repository;

import com.danalfintech.cryptotax.exchange.common.entity.Exchange;
import com.danalfintech.cryptotax.exchange.common.entity.ExchangeApiKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExchangeApiKeyRepository extends JpaRepository<ExchangeApiKey, Long> {

    // 특정 유저가 등록한 모든 거래소 키 목록 조회
    List<ExchangeApiKey> findAllByUserId(Long userId);

    // 이미 등록된 키인지 확인
    boolean existsByUserIdAndExchange(Long userId, Exchange exchange);

    // 상세 조회 및 중복 체크용
    Optional<ExchangeApiKey> findByUserIdAndExchange(Long userId, Exchange exchange);

    // 소유자 검증 포함 단건 조회
    Optional<ExchangeApiKey> findByIdAndUserId(Long id, Long userId);

}