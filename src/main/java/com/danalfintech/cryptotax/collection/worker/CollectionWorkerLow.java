package com.danalfintech.cryptotax.collection.worker;

import com.danalfintech.cryptotax.collection.dto.CollectionMessage;
import com.danalfintech.cryptotax.global.config.RabbitConfig;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CollectionWorkerLow {

    private final CollectionProcessor processor;

    @RabbitListener(
            queues = {RabbitConfig.QUEUE_LOW},
            concurrency = "6",
            ackMode = "MANUAL"
    )
    public void onMessage(CollectionMessage message, Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        processor.process(message, channel, deliveryTag);
    }
}