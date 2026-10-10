package com.dalai.llama.tenant.youtube.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({YouTubeProperties.class, com.dalai.llama.tenant.youtube.oauth.GoogleOAuthProperties.class})
public class YouTubeConfig {
}
