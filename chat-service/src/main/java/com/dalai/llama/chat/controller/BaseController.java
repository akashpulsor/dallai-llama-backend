package com.dalai.llama.chat.controller;

import com.dalai.llama.chat.service.ChatException;
import com.dalai.llama.chat.web.TenantContext;
import com.dalai.llama.chat.web.TenantContextHolder;
import org.springframework.http.HttpStatus;

abstract class BaseController {

    TenantContext tenant() {
        TenantContext context = TenantContextHolder.get();
        if (context == null || context.tenantId() == null) {
            throw new ChatException(HttpStatus.UNAUTHORIZED, "Missing X-Tenant-ID");
        }
        return context;
    }
}
