package com.dalai.llama.creativeplanning.controller;

import com.dalai.llama.creativeplanning.service.CreativePlanningException;
import com.dalai.llama.creativeplanning.web.TenantContext;
import com.dalai.llama.creativeplanning.web.TenantContextHolder;
import org.springframework.http.HttpStatus;

abstract class BaseController {

    TenantContext tenant() {
        TenantContext context = TenantContextHolder.get();
        if (context == null || context.tenantId() == null) {
            throw new CreativePlanningException(HttpStatus.UNAUTHORIZED, "Missing X-Tenant-ID");
        }
        return context;
    }
}
