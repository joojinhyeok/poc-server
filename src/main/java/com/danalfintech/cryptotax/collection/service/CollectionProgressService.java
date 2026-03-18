package com.danalfintech.cryptotax.collection.service;

import com.danalfintech.cryptotax.collection.domain.CollectionJob;
import com.danalfintech.cryptotax.collection.domain.CollectionJobRepository;
import com.danalfintech.cryptotax.collection.dto.CollectionStatusResponse;
import com.danalfintech.cryptotax.collection.dto.ProgressData;
import com.danalfintech.cryptotax.global.error.BusinessException;
import com.danalfintech.cryptotax.global.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class CollectionProgressService {

    private static final String PROGRESS_KEY_PREFIX = "collection:progress:";
    private static final Duration PROGRESS_TTL = Duration.ofHours(2);

    private final RedisTemplate<String, String> redisTemplate;
    private final CollectionJobRepository collectionJobRepository;
    private final ObjectMapper objectMapper;

    public void initProgress(Long jobId) {
        try {
            ProgressData data = new ProgressData("PENDING", 0, 0, 0);
            String json = objectMapper.writeValueAsString(data);
            redisTemplate.opsForValue().set(progressKey(jobId), json, PROGRESS_TTL);
        } catch (Exception e) {
            log.warn("Redis progress 초기화 실패: jobId={}", jobId, e);
        }
    }

    public void updateProgress(Long jobId, String status, int totalSymbols, int processedSymbols, int newTradesCount) {
        try {
            ProgressData data = new ProgressData(status, totalSymbols, processedSymbols, newTradesCount);
            String json = objectMapper.writeValueAsString(data);
            redisTemplate.opsForValue().set(progressKey(jobId), json, PROGRESS_TTL);
        } catch (Exception e) {
            log.warn("Redis progress 업데이트 실패: jobId={}", jobId, e);
        }
    }

    public void deleteProgress(Long jobId) {
        try {
            redisTemplate.delete(progressKey(jobId));
        } catch (Exception e) {
            log.warn("Redis progress 삭제 실패: jobId={}", jobId, e);
        }
    }

    public CollectionStatusResponse getStatus(Long jobId, Long userId) {
        // 소유권 검증 + DB 조회
        CollectionJob job = collectionJobRepository.findByIdAndUserId(jobId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COLLECTION_JOB_NOT_FOUND));

        // Redis 우선 조회 — 실시간 진행률 반영
        try {
            String json = redisTemplate.opsForValue().get(progressKey(jobId));
            if (json != null) {
                ProgressData progress = objectMapper.readValue(json, ProgressData.class);
                return CollectionStatusResponse.fromWithProgress(job, progress);
            }
        } catch (Exception e) {
            log.warn("Redis progress 조회 실패, DB fallback: jobId={}", jobId, e);
        }

        // DB fallback
        return CollectionStatusResponse.from(job);
    }

    private String progressKey(Long jobId) {
        return PROGRESS_KEY_PREFIX + jobId;
    }
}