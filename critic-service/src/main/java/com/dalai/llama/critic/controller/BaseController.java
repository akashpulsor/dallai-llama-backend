package com.dalai.llama.critic.controller;

import com.dalai.llama.critic.service.CriticException;
import com.dalai.llama.critic.web.TenantContext;
import com.dalai.llama.critic.web.TenantContextHolder;
import org.springframework.http.HttpStatus;

abstract class BaseController {

    TenantContext tenant() {
        TenantContext context = TenantContextHolder.get();
        if (context == null || context.tenantId() == null) {
            throw new CriticException(HttpStatus.UNAUTHORIZED, "Missing X-Tenant-ID");
        }
        return context;
    }
}
