package com.danalfintech.cryptotax.global.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    public static final String EXCHANGE_COLLECTION = "exchange.collection";
    public static final String EXCHANGE_COLLECTION_DLQ = "exchange.collection.dlq";

    public static final String QUEUE_HIGH = "queue.collection.high";
    public static final String QUEUE_LOW = "queue.collection.low";
    public static final String QUEUE_DLQ = "queue.collection.dlq";

    public static final String ROUTING_HIGH = "collection.high";
    public static final String ROUTING_LOW = "collection.low";
    public static final String ROUTING_DLQ = "collection.dlq";

    @Bean
    public MessageConverter messageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    @Bean
    DirectExchange collectionExchange() {
        return new DirectExchange(EXCHANGE_COLLECTION);
    }

    @Bean
    DirectExchange collectionDlxExchange() {
        return new DirectExchange(EXCHANGE_COLLECTION_DLQ);
    }

    @Bean
    Queue highQueue() {
        return QueueBuilder.durable(QUEUE_HIGH)
                .withArgument("x-dead-letter-exchange", EXCHANGE_COLLECTION_DLQ)
                .withArgument("x-dead-letter-routing-key", ROUTING_DLQ)
                .build();
    }

    @Bean
    Queue lowQueue() {
        return QueueBuilder.durable(QUEUE_LOW)
                .withArgument("x-dead-letter-exchange", EXCHANGE_COLLECTION_DLQ)
                .withArgument("x-dead-letter-routing-key", ROUTING_DLQ)
                .build();
    }

    @Bean
    Queue dlqQueue() {
        return QueueBuilder.durable(QUEUE_DLQ).build();
    }

    @Bean
    Binding highBinding() {
        return BindingBuilder.bind(highQueue()).to(collectionExchange()).with(ROUTING_HIGH);
    }

    @Bean
    Binding lowBinding() {
        return BindingBuilder.bind(lowQueue()).to(collectionExchange()).with(ROUTING_LOW);
    }

    @Bean
    Binding dlqBinding() {
        return BindingBuilder.bind(dlqQueue()).to(collectionDlxExchange()).with(ROUTING_DLQ);
    }
}