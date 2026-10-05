package com.dalai.llama.preprod.controller;

import com.dalai.llama.preprod.dto.AdminProjectView;
import com.dalai.llama.preprod.service.AdminProjectService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Project names for the ops page (served on ops.dalaillama.in behind the ops login, via the
 * existing /api/v1/internal/admin/production route) -- so cost and P&L views can say which film a
 * project id is. */
@RestController
@RequestMapping("/api/v1/internal/admin/production/projects")
public class AdminProjectController {

    private final AdminProjectService adminProjectService;

    public AdminProjectController(AdminProjectService adminProjectService) {
        this.adminProjectService = adminProjectService;
    }

    @GetMapping
    public ResponseEntity<List<AdminProjectView>> list(@RequestParam("tenantId") UUID tenantId) {
        return ResponseEntity.ok(adminProjectService.list(tenantId));
    }
}
