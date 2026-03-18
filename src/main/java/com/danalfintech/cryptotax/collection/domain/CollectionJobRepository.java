package com.danalfintech.cryptotax.collection.domain;

import com.danalfintech.cryptotax.exchange.common.Exchange;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface CollectionJobRepository extends JpaRepository<CollectionJob, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT j FROM CollectionJob j WHERE j.userId = :userId AND j.exchange = :exchange AND j.status IN :statuses")
    Optional<CollectionJob> findByUserIdAndExchangeAndStatusInForUpdate(
            Long userId, Exchange exchange, List<CollectionJobStatus> statuses);

    List<CollectionJob> findAllByUserIdAndStatusIn(
            Long userId, List<CollectionJobStatus> statuses);

    Optional<CollectionJob> findByIdAndUserId(Long id, Long userId);
}