package com.dalai.llama.tenant.leadmanagement.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers {@link LeadManagementProperties} for {@code @Autowired} injection. Kept as a
 * dedicated @Configuration class rather than a class-level annotation on the properties record
 * so the config surface is discoverable via a single grep for the module. */
@Configuration
@EnableConfigurationProperties(LeadManagementProperties.class)
public class LeadManagementConfig {
}
