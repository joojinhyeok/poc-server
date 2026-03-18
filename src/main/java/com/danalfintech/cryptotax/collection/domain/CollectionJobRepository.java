package com.danalfintech.cryptotax.collection.domain;

import com.danalfintech.cryptotax.exchange.common.Exchange;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CollectionJobRepository extends JpaRepository<CollectionJob, Long> {

    Optional<CollectionJob> findByUserIdAndExchangeAndStatusIn(
            Long userId, Exchange exchange, List<CollectionJobStatus> statuses);

    List<CollectionJob> findAllByUserIdAndStatusIn(
            Long userId, List<CollectionJobStatus> statuses);

    Optional<CollectionJob> findByIdAndUserId(Long id, Long userId);
}
