package com.dalai.llama.preprod.controller;

import com.dalai.llama.preprod.dto.ClonedVoiceIdentityView;
import com.dalai.llama.preprod.dto.PersistClonedVoiceRequest;
import com.dalai.llama.preprod.service.CastProfileService;
import com.dalai.llama.preprod.service.CastVoiceReferenceService;
import jakarta.validation.Valid;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Internal, tenant-scoped write API used by video-generation after a provider creates a voice. */
@RestController
@RequestMapping("/api/v1/internal/tenants/{tenantId}/projects/{projectId}/cast-profiles")
public class InternalCastProfileController {

    private final CastProfileService castProfileService;
    private final CastVoiceReferenceService castVoiceReferenceService;

    public InternalCastProfileController(
            CastProfileService castProfileService,
            CastVoiceReferenceService castVoiceReferenceService
    ) {
        this.castProfileService = castProfileService;
        this.castVoiceReferenceService = castVoiceReferenceService;
    }

    /**
     * Claims a clone identity only while the profile has none. Replaying the same request, or a
     * concurrent request that loses the race, returns the already-stored identity unchanged.
     */
    @PutMapping("/{castProfileId}/cloned-voice")
    public ResponseEntity<ClonedVoiceIdentityView> persistClonedVoiceIfAbsent(
            @PathVariable UUID tenantId,
            @PathVariable UUID projectId,
            @PathVariable UUID castProfileId,
            @Valid @RequestBody PersistClonedVoiceRequest request) {
        return ResponseEntity.ok(castProfileService.persistClonedVoiceIfAbsent(tenantId, projectId, castProfileId, request));
    }

    /** Streams the original voice asset after checking that the requested cast profile is usable
     * for this tenant/project. The filename and media type are preserved for provider upload. */
    @GetMapping("/{castProfileId}/voice-reference")
    public ResponseEntity<InputStreamResource> voiceReference(
            @PathVariable UUID tenantId,
            @PathVariable UUID projectId,
            @PathVariable UUID castProfileId
    ) {
        CastVoiceReferenceService.VoiceReference voiceReference = castVoiceReferenceService.open(
                tenantId, projectId, castProfileId);
        return ResponseEntity.ok()
                .contentType(mediaType(voiceReference.contentType()))
                .contentLength(voiceReference.contentLength())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(voiceReference.filename())
                        .build()
                        .toString())
                .body(new InputStreamResource(voiceReference.content()));
    }

    private MediaType mediaType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        try {
            return MediaType.parseMediaType(contentType);
        } catch (IllegalArgumentException ignored) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
