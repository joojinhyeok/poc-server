package com.danalfintech.cryptotax.global.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.exchange")
public class ExchangeProperties {

    private Map<String, Integer> maxConcurrent = new HashMap<>();
    private Map<String, RateLimitConfig> rateLimit = new HashMap<>();

    @Getter
    @Setter
    public static class RateLimitConfig {
        private String type;
        private int limit;
        private int windowSeconds;
    }
}
