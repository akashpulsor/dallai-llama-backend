package com.dalai.llama.creator.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CreatorWebMvcConfig implements WebMvcConfigurer {

    private final BillingWalletGuardInterceptor billingWalletGuardInterceptor;

    public CreatorWebMvcConfig(BillingWalletGuardInterceptor billingWalletGuardInterceptor) {
        this.billingWalletGuardInterceptor = billingWalletGuardInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(billingWalletGuardInterceptor)
                .addPathPatterns("/api/v1/creator/**");
    }
}
