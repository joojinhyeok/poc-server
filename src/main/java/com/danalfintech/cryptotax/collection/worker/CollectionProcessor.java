package com.danalfintech.cryptotax.collection.worker;

import com.danalfintech.cryptotax.collection.dto.CollectionMessage;
import com.danalfintech.cryptotax.exchange.common.entity.Exchange;
import com.danalfintech.cryptotax.global.config.ExchangeProperties;
import com.danalfintech.cryptotax.global.infra.exchange.ExchangeContext;
import com.danalfintech.cryptotax.global.infra.redis.ExchangeLeaseManager;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
public class CollectionProcessor {

    private final ExchangeLeaseManager leaseManager;
    private final CollectionTransactionService transactionService;
    private final ExchangeProperties exchangeProperties;
    private final Map<Exchange, Integer> maxConcurrentMap = new EnumMap<>(Exchange.class);

    public CollectionProcessor(
            ExchangeLeaseManager leaseManager,
            CollectionTransactionService transactionService,
            ExchangeProperties exchangeProperties) {
        this.leaseManager = leaseManager;
        this.transactionService = transactionService;
        this.exchangeProperties = exchangeProperties;
    }

    @PostConstruct
    public void init() {
        exchangeProperties.getMaxConcurrent().forEach((key, value) -> {
            try {
                maxConcurrentMap.put(Exchange.valueOf(key), value);
            } catch (IllegalArgumentException e) {
                log.warn("알 수 없는 거래소 설정: {}", key);
            }
        });
    }

    public void process(CollectionMessage message, Channel channel, long deliveryTag) {
        String workerId = UUID.randomUUID().toString();
        Exchange exchange = message.exchange();
        Long jobId = message.jobId();

        // v1: serverIp null. v2: message 또는 apiKey에서 serverIp를 읽어 생성
        ExchangeContext ctx = ExchangeContext.of(exchange);

        log.info("수집 메시지 수신: jobId={}, exchange={}, type={}", jobId, exchange, message.type());

        int maxConcurrent = maxConcurrentMap.getOrDefault(exchange, 3);

        // 1. Lease 획득
        if (!leaseManager.tryAcquire(ctx, workerId, maxConcurrent)) {
            log.info("Lease 획득 실패, 재큐잉: jobId={}, exchange={}", jobId, exchange);
            nack(channel, deliveryTag, true);
            return;
        }

        // 2. Heartbeat 시작 (Virtual Thread)
        Thread heartbeatThread = Thread.ofVirtual().start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(Duration.ofSeconds(30));
                    leaseManager.heartbeat(ctx, workerId);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });

        try {
            transactionService.processCollection(message);
            ack(channel, deliveryTag);
        } catch (Exception e) {
            log.error("수집 처리 실패: jobId={}, exchange={}", jobId, exchange, e);
            try {
                transactionService.handleFailure(jobId, e.getMessage());
            } catch (Exception ex) {
                log.error("실패 처리 중 오류: jobId={}", jobId, ex);
            }
            nack(channel, deliveryTag, false);
        } finally {
            heartbeatThread.interrupt();
            leaseManager.release(ctx, workerId);
        }
    }

    private void ack(Channel channel, long deliveryTag) {
        try {
            channel.basicAck(deliveryTag, false);
        } catch (IOException e) {
            log.error("ACK 실패", e);
        }
    }

    private void nack(Channel channel, long deliveryTag, boolean requeue) {
        try {
            channel.basicNack(deliveryTag, false, requeue);
        } catch (IOException e) {
            log.error("NACK 실패", e);
        }
    }
}
