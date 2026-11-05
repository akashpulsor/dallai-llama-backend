package com.dalai.llama.tenant.leadmanagement.creatormail;

import com.dalai.llama.tenant.leadmanagement.brand.BrandMailPriorityService;
import com.dalai.llama.tenant.leadmanagement.brand.BrandMailPriorityService.BrandPriorities;
import com.dalai.llama.tenant.leadmanagement.creatormail.BrandFollowUpService.FollowUpSettings;
import com.dalai.llama.tenant.leadmanagement.creatormail.BrandFollowUpService.FollowUpView;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Ops page "Mail brands" tab: the follow-up funnel, its templates and its on/off switch.
 * Under the ops-routed {@code /api/v1/internal/admin/tenants/**}. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/internal/admin/tenants/creator-mail/follow-up")
public class AdminBrandFollowUpController {

    private final BrandFollowUpService service;
    private final BrandMailPriorityService priorities;

    @GetMapping
    public ResponseEntity<FollowUpView> view() {
        return ResponseEntity.ok(service.view());
    }

    /** What brands asked to see, for ops to find videos by. */
    @GetMapping("/priorities")
    public ResponseEntity<List<BrandPriorities>> priorities() {
        return ResponseEntity.ok(priorities.all());
    }

    @PutMapping
    public ResponseEntity<FollowUpView> save(@RequestBody FollowUpSettings settings) {
        return ResponseEntity.ok(service.save(settings));
    }
}
