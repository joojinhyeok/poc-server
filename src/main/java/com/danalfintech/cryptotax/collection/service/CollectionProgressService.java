package com.danalfintech.cryptotax.collection.service;

import com.danalfintech.cryptotax.collection.domain.CollectionJob;
import com.danalfintech.cryptotax.collection.domain.CollectionJobRepository;
import com.danalfintech.cryptotax.collection.dto.CollectionStatusResponse;
import com.danalfintech.cryptotax.global.error.BusinessException;
import com.danalfintech.cryptotax.global.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;

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
            Map<String, Object> progress = Map.of(
                    "totalSymbols", 0,
                    "processedSymbols", 0,
                    "newTradesCount", 0,
                    "status", "PENDING"
            );
            String json = objectMapper.writeValueAsString(progress);
            redisTemplate.opsForValue().set(progressKey(jobId), json, PROGRESS_TTL);
        } catch (Exception e) {
            log.warn("Redis progress 초기화 실패: jobId={}", jobId, e);
        }
    }

    public void updateProgress(Long jobId, String status, int totalSymbols, int processedSymbols, int newTradesCount) {
        try {
            Map<String, Object> progress = Map.of(
                    "totalSymbols", totalSymbols,
                    "processedSymbols", processedSymbols,
                    "newTradesCount", newTradesCount,
                    "status", status
            );
            String json = objectMapper.writeValueAsString(progress);
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

    public CollectionStatusResponse getStatus(Long jobId) {
        // Redis 우선 조회
        try {
            String json = redisTemplate.opsForValue().get(progressKey(jobId));
            if (json != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> progress = objectMapper.readValue(json, Map.class);
                CollectionJob job = collectionJobRepository.findById(jobId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.COLLECTION_JOB_NOT_FOUND));
                return CollectionStatusResponse.from(job);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Redis progress 조회 실패, DB fallback: jobId={}", jobId, e);
        }

        // DB fallback
        CollectionJob job = collectionJobRepository.findById(jobId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COLLECTION_JOB_NOT_FOUND));
        return CollectionStatusResponse.from(job);
    }

    private String progressKey(Long jobId) {
        return PROGRESS_KEY_PREFIX + jobId;
    }
}