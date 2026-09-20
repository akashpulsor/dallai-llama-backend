package com.dalai.llama.observability;

import jakarta.servlet.Filter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

/**
 * Wires {@link TenantMdcFilter} into every service that puts observability-common on its
 * classpath, without any @Import or explicit @Bean declaration in the consuming service. The
 * conditions keep this a servlet-web-only concern: a reactive-only or non-web module that
 * happens to transitively pull this in stays untouched.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(Filter.class)
public class ObservabilityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TenantMdcFilter tenantMdcFilter() {
        return new TenantMdcFilter();
    }
}
