package com.dalai.llama.creator.controller;

import com.dalai.llama.creator.dto.request.CastProfileRequest;
import com.dalai.llama.creator.dto.response.CastProfileResponse;
import com.dalai.llama.creator.service.CreatorProfileService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/creator/profiles")
public class CreatorProfileController {

    private final CreatorProfileService profileService;

    public CreatorProfileController(CreatorProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping
    public ResponseEntity<List<CastProfileResponse>> listProfiles(
            @RequestParam(value = "projectId", required = false) UUID projectId,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(profileService.listProfiles(projectId, tenantId, userId));
    }

    @PostMapping
    public ResponseEntity<CastProfileResponse> createProfile(
            @Valid @RequestBody CastProfileRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(profileService.createProfile(request, tenantId, userId));
    }

    @PatchMapping("/{profileId}")
    public ResponseEntity<CastProfileResponse> updateProfile(
            @PathVariable UUID profileId,
            @Valid @RequestBody CastProfileRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(profileService.updateProfile(profileId, request, tenantId, userId));
    }
}
