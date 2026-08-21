package com.dalai.llama.trendintel.controller;

import com.dalai.llama.trendintel.service.TrendIntelligenceException;
import com.dalai.llama.trendintel.web.TenantContext;
import com.dalai.llama.trendintel.web.TenantContextHolder;
import org.springframework.http.HttpStatus;

abstract class BaseController {

    TenantContext tenant() {
        TenantContext context = TenantContextHolder.get();
        if (context == null || context.tenantId() == null) {
            throw new TrendIntelligenceException(HttpStatus.UNAUTHORIZED, "Missing X-Tenant-ID");
        }
        return context;
    }
}
