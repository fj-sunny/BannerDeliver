package com.bannerdeliver.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/** Kafka 消费者配置：手动 ack 与失败固定间隔重投。 */
@Configuration
public class KafkaConsumerConfig {

    /** 创建 Kafka 消费失败时的错误处理器，不提交 offset 并无限重试。 */
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

    /** 配置 Kafka 监听容器工厂，启用手动 ack 并绑定错误处理器。 */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            DefaultErrorHandler bannerKafkaErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setCommonErrorHandler(bannerKafkaErrorHandler);
        return factory;
    }
}
