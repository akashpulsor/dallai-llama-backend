package com.dalai.llama.preprod.controller;

import com.dalai.llama.preprod.dto.ChangeRequestView;
import com.dalai.llama.preprod.service.ChangeRequestService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
public class ChangeRequestController extends BaseController {

    private final ChangeRequestService changeRequestService;

    public ChangeRequestController(ChangeRequestService changeRequestService) {
        this.changeRequestService = changeRequestService;
    }

    @GetMapping("/v1/projects/{projectId}/change-requests")
    public ResponseEntity<List<ChangeRequestView>> list(@PathVariable UUID projectId) {
        return ResponseEntity.ok(changeRequestService.list(tenant().tenantId(), projectId));
    }

    /** {@code files} is optional -- a plain apply (no attachment) still posts with no body, same
     * as before this existed; SHOT_IMAGE only, see {@link
     * ChangeRequestService#apply(UUID, UUID, UUID, List)}. */
    @PostMapping("/v1/projects/{projectId}/change-requests/{changeRequestId}/apply")
    public ResponseEntity<ChangeRequestView> apply(
            @PathVariable UUID projectId, @PathVariable UUID changeRequestId,
            @RequestParam(required = false) List<MultipartFile> files) {
        return ResponseEntity.ok(changeRequestService.apply(tenant().tenantId(), projectId, changeRequestId, files == null ? List.of() : files));
    }

    @PostMapping("/v1/projects/{projectId}/change-requests/{changeRequestId}/dismiss")
    public ResponseEntity<ChangeRequestView> dismiss(@PathVariable UUID projectId, @PathVariable UUID changeRequestId) {
        return ResponseEntity.ok(changeRequestService.dismiss(tenant().tenantId(), projectId, changeRequestId));
    }
}
