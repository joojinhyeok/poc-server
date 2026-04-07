package com.danalfintech.cryptotax.collection.domain;

import com.danalfintech.cryptotax.exchange.common.entity.Exchange;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CollectionJobRepository extends JpaRepository<CollectionJob, Long> {

    Optional<CollectionJob> findByUserIdAndExchangeAndStatusIn(
            Long userId, Exchange exchange, List<CollectionJobStatus> statuses);

    List<CollectionJob> findAllByUserIdAndStatusIn(
            Long userId, List<CollectionJobStatus> statuses);

    Optional<CollectionJob> findByIdAndUserId(Long id, Long userId);

    /** 심볼 성공 시: processedSymbols + 1, newTradesCount + trades. 원자적 증가 후 현재 processedSymbols 반환 */
    @Query(value = """
            UPDATE collection_jobs
            SET processed_symbols = processed_symbols + 1,
                new_trades_count  = new_trades_count + :trades
            WHERE id = :jobId
            RETURNING processed_symbols
            """, nativeQuery = true)
    int incrementProgressAndGet(@Param("jobId") Long jobId, @Param("trades") int trades);

    /** 심볼 실패 시: processedSymbols + 1, failedSymbols + 1. 원자적 증가 후 현재 processedSymbols 반환 */
    @Query(value = """
            UPDATE collection_jobs
            SET processed_symbols = processed_symbols + 1,
                failed_symbols    = failed_symbols + 1
            WHERE id = :jobId
            RETURNING processed_symbols
            """, nativeQuery = true)
    int incrementFailureAndGet(@Param("jobId") Long jobId);
}
