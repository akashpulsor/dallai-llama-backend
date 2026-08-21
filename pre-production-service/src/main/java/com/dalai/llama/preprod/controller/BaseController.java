package com.dalai.llama.preprod.controller;

import com.dalai.llama.preprod.service.PreProductionException;
import com.dalai.llama.preprod.web.TenantContext;
import com.dalai.llama.preprod.web.TenantContextHolder;
import org.springframework.http.HttpStatus;

abstract class BaseController {

    TenantContext tenant() {
        TenantContext context = TenantContextHolder.get();
        if (context == null || context.tenantId() == null) {
            throw new PreProductionException(HttpStatus.UNAUTHORIZED, "Missing X-Tenant-ID");
        }
        return context;
    }
}
