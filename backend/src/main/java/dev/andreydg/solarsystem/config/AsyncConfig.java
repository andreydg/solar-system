package dev.andreydg.solarsystem.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * Dedicated, bounded pool for JPL validation so a burst of generated events cannot spawn an
     * unbounded number of concurrent validation tasks. Outbound request concurrency is further
     * capped by the semaphore in JplHorizonsClient; work beyond the pool size queues rather than
     * running live.
     */
    @Bean("jplValidationExecutor")
    public Executor jplValidationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("jpl-validation-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(500);
        executor.initialize();
        return executor;
    }
}
