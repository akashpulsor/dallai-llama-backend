package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorAsset;
import com.dalai.llama.creator.domain.entity.CreatorScript;
import com.dalai.llama.creator.repository.CreatorAssetRepository;
import com.dalai.llama.creator.repository.CreatorScriptRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Characterization tests for ReferenceAssetUploader - the extraction of ScreenplayVideoService's
 * product/style reference-image upload flow (uploadReferenceImage), zero coverage before this
 * move.
 */
class ReferenceAssetUploaderTest {

    private final ScreenplayVideoService owner = mock(ScreenplayVideoService.class);
    private final CreatorAssetRepository assetRepository = mock(CreatorAssetRepository.class);
    private final AssetStorageService assetStorageService = mock(AssetStorageService.class);
    private final CreatorScriptRepository scriptRepository = mock(CreatorScriptRepository.class);
    private final ReferenceAssetUploader uploader = new ReferenceAssetUploader(owner, assetRepository, assetStorageService, scriptRepository);

    @Test
    void uploadReferenceImage_throwsBadRequestWhenFileIsEmpty() {
        when(owner.loadScript(any(), any(), any())).thenReturn(script());
        MultipartFile emptyFile = mock(MultipartFile.class);
        when(emptyFile.isEmpty()).thenReturn(true);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> uploader.uploadReferenceImage(UUID.randomUUID(), emptyFile, "details", false, "tenant", "user"));
        assertTrue(ex.getReason().contains("Upload a product or style reference image"));
    }

    @Test
    void uploadReferenceImage_throwsBadRequestForNonImageContentType() throws Exception {
        when(owner.loadScript(any(), any(), any())).thenReturn(script());
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getContentType()).thenReturn("application/pdf");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> uploader.uploadReferenceImage(UUID.randomUUID(), file, "details", false, "tenant", "user"));
        assertTrue(ex.getReason().contains("JPG, PNG, WebP, AVIF, or GIF"));
    }

    @Test
    void uploadReferenceImage_storesAssetAndAttachesToScriptOnSuccess() throws Exception {
        CreatorScript script = script();
        when(owner.loadScript(any(), any(), any())).thenReturn(script);

        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getContentType()).thenReturn("image/png");
        when(file.getOriginalFilename()).thenReturn("Product Shot.png");
        when(file.getBytes()).thenReturn(new byte[]{1, 2, 3});

        AssetStorageService.StoredObject stored = new AssetStorageService.StoredObject(
                "bucket", "screenplay-videos/x/reference-images/y.png", "image/png", 3L, "https://cdn/y.png"
        );
        when(assetStorageService.uploadCreatorAsset(any(), any(), any(), any())).thenReturn(stored);

        when(assetRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            CreatorAsset asset = invocation.getArgument(0);
            asset.setId(UUID.randomUUID());
            return asset;
        });

        Map<String, Object> response = uploader.uploadReferenceImage(script.getId(), file, "hero shot", true, "tenant", "user");

        assertEquals("https://cdn/y.png", response.get("assetUrl"));
        assertEquals("SCREENPLAY_REFERENCE_IMAGE", response.get("assetType"));
        assertEquals(true, response.get("scriptReferenceUpdated"));
        assertEquals("hero shot", response.get("details"));

        verify(scriptRepository).saveAndFlush(any());
        @SuppressWarnings("unchecked")
        Map<String, Object> savedPayload = (Map<String, Object>) script.getScriptPayload();
        assertTrue(((List<?>) savedPayload.get("referenceImageUrls")).contains("https://cdn/y.png"));
        assertTrue(((List<?>) savedPayload.get("productImageUrls")).contains("https://cdn/y.png"));
    }

    private CreatorScript script() {
        return CreatorScript.builder()
                .id(UUID.randomUUID())
                .tenantId("tenant")
                .userId("user")
                .title("Founder script")
                .scriptPayload(new LinkedHashMap<>())
                .shots(List.of())
                .status("GENERATED")
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();
    }
}
