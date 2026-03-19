package com.dalai.llama.pbx.core.config;


import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Configuration for asynchronous task execution (e.g., handling FreeSWITCH ESL events).
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * Custom ThreadPool for processing ESL events asynchronously.
     * Can be used via @Async("eslEventTaskExecutor")
     */
    @Bean(name = "eslEventTaskExecutor")
    public Executor eslEventTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(50);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("ESL-Event-");
        executor.initialize();
        return executor;
    }
}
