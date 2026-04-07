package com.danalfintech.cryptotax.collection.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CollectionFailureRepository extends JpaRepository<CollectionFailure, Long> {

    List<CollectionFailure> findAllByJobId(Long jobId);

    List<CollectionFailure> findAllByJobIdAndRetriedFalse(Long jobId);

    int countByJobId(Long jobId);
}
