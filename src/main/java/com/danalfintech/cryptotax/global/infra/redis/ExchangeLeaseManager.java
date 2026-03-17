package com.danalfintech.cryptotax.global.infra.redis;

import com.danalfintech.cryptotax.exchange.common.Exchange;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;

@Slf4j
@Component
@EnableScheduling
@RequiredArgsConstructor
public class ExchangeLeaseManager {

    private static final long LEASE_TTL_MILLIS = 60_000L;
    private static final String KEY_PREFIX = "exchange:leases:";

    private final RedisTemplate<String, String> redisTemplate;

    private DefaultRedisScript<Long> acquireScript;
    private DefaultRedisScript<Long> releaseScript;

    @PostConstruct
    public void init() {
        acquireScript = new DefaultRedisScript<>();
        acquireScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("scripts/lease-acquire.lua")));
        acquireScript.setResultType(Long.class);

        releaseScript = new DefaultRedisScript<>();
        releaseScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("scripts/lease-release.lua")));
        releaseScript.setResultType(Long.class);
    }

    public boolean tryAcquire(Exchange exchange, String workerId, int maxConcurrent) {
        try {
            long now = Instant.now().toEpochMilli();
            long expireTime = now + LEASE_TTL_MILLIS;
            Long result = redisTemplate.execute(
                    acquireScript,
                    Collections.singletonList(leaseKey(exchange)),
                    String.valueOf(maxConcurrent),
                    workerId,
                    String.valueOf(expireTime),
                    String.valueOf(now)
            );
            return result != null && result == 1L;
        } catch (Exception e) {
            log.warn("Redis lease acquire 실패, fail-open 처리: exchange={}, workerId={}", exchange, workerId, e);
            return true;
        }
    }

    public void release(Exchange exchange, String workerId) {
        try {
            redisTemplate.execute(
                    releaseScript,
                    Collections.singletonList(leaseKey(exchange)),
                    workerId
            );
        } catch (Exception e) {
            log.warn("Redis lease release 실패: exchange={}, workerId={}", exchange, workerId, e);
        }
    }

    public void heartbeat(Exchange exchange, String workerId) {
        try {
            long expireTime = Instant.now().toEpochMilli() + LEASE_TTL_MILLIS;
            redisTemplate.opsForHash().put(leaseKey(exchange), workerId, String.valueOf(expireTime));
        } catch (Exception e) {
            log.warn("Redis lease heartbeat 실패: exchange={}, workerId={}", exchange, workerId, e);
        }
    }

    @Scheduled(fixedRate = 10_000)
    public void cleanupExpiredLeases() {
        for (Exchange exchange : Exchange.values()) {
            try {
                String key = leaseKey(exchange);
                Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);
                long now = Instant.now().toEpochMilli();

                for (Map.Entry<Object, Object> entry : entries.entrySet()) {
                    long expireTime = Long.parseLong(entry.getValue().toString());
                    if (expireTime < now) {
                        redisTemplate.opsForHash().delete(key, entry.getKey());
                        log.info("만료된 lease 정리: exchange={}, workerId={}", exchange, entry.getKey());
                    }
                }
            } catch (Exception e) {
                log.warn("Lease 정리 스케줄러 실패: exchange={}", exchange, e);
            }
        }
    }

    private String leaseKey(Exchange exchange) {
        return KEY_PREFIX + exchange.name();
    }
}