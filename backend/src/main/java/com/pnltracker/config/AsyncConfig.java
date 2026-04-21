package com.pnltracker.config;

import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class AsyncConfig {

    @Bean(name = "alchemyExecutor")
    public Executor alchemyExecutor(PortfolioProperties properties) {
        PortfolioProperties.AsyncProperties async = properties.getAsync();
        int concurrency = Math.max(1, async.getAlchemyConcurrency());

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("alchemy-");
        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency);
        executor.setQueueCapacity(Math.max(0, async.getAlchemyQueueCapacity()));
        executor.initialize();
        return executor;
    }
}
