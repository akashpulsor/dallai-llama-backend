package com.dalai.llama.creator.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class CreatorAsyncConfig {

    @Bean(name = "creatorTaskExecutor")
    public TaskExecutor creatorTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(6);
        executor.setQueueCapacity(40);
        executor.setThreadNamePrefix("creator-ai-");
        executor.initialize();
        return executor;
    }
}
