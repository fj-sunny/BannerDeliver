package com.bannerdeliver.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaConsumerConfig {

    @Bean
    public DefaultErrorHandler bannerKafkaErrorHandler(BannerProperties properties) {
        FixedBackOff backOff = new FixedBackOff(
                properties.getKafka().getRetryBackoffMs(),
                FixedBackOff.UNLIMITED_ATTEMPTS);
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(backOff);
        errorHandler.setCommitRecovered(false);
        errorHandler.setSeekAfterError(true);
        return errorHandler;
    }
}
