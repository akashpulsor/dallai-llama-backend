package com.dalai.llama.tenant.showcase.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Registers {@link ShowcaseProperties}; same one-class-per-module convention as
 * {@code LeadManagementConfig}. Also provides the {@link Clock} the showcase services read the
 * time from, so cooldowns and windows are testable without sleeping. */
@Configuration
@EnableConfigurationProperties({ShowcaseProperties.class, VideoHostProperties.class, RankingProperties.class})
public class ShowcaseConfig {

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
