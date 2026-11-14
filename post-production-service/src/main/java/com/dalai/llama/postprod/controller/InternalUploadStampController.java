package com.dalai.llama.postprod.controller;

import com.dalai.llama.postprod.service.clip.UploadStampService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** tenant-service asks for a creator's uploaded film to be stamped with the Dalaillama mark before
 * it uploads it to Dalaillama's YouTube channel, then polls for the stamped copy. Mesh-internal. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/internal/upload-stamps/{id}")
public class InternalUploadStampController {

    private final UploadStampService uploadStamps;

    public record StampRequestBody(@NotNull UUID tenantId, @NotBlank String bucket, @NotBlank String objectKey, Integer width) {
    }

    /** Idempotent: the same id is the same request. */
    @PutMapping
    public UploadStampService.StampView request(@PathVariable UUID id, @Valid @RequestBody StampRequestBody body) {
        return uploadStamps.request(id, new UploadStampService.StampRequest(body.tenantId(), body.bucket(), body.objectKey(), body.width()));
    }

    @GetMapping
    public ResponseEntity<UploadStampService.StampView> find(@PathVariable UUID id) {
        return ResponseEntity.of(uploadStamps.find(id));
    }
}
