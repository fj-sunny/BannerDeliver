package com.bannerdeliver.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** 人群包 Redis 并行写入线程池。 */
@Configuration
public class AudienceWriteConfig {

    /** 固定大小线程池；大小由 {@code banner.audience.write-parallelism} 决定。 */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService audienceWriteExecutor(BannerProperties properties) {
        int parallelism = properties.getAudience().getWriteParallelism();
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("audience-write-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newFixedThreadPool(parallelism, threadFactory);
    }
}
