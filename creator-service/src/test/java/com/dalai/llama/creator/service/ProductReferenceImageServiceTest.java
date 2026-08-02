package com.dalai.llama.creator.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductReferenceImageServiceTest {

    @Test
    void streamsMultipleImagesToCreatorStorageAndReturnsReusableAssetMetadata() {
        AssetStorageService storage = mock(AssetStorageService.class);
        when(storage.uploadCreatorAssetFromStream(anyString(), any(InputStream.class), anyString(), any(Duration.class)))
                .thenAnswer(invocation -> new AssetStorageService.StoredObject(
                        "creator-assets",
                        invocation.getArgument(0),
                        invocation.getArgument(2),
                        4L,
                        "https://media.example/" + invocation.getArgument(0)
                ));
        ProductReferenceImageService service = new ProductReferenceImageService(storage);

        Map<String, Object> response = service.upload(List.of(
                new MockMultipartFile("files", "front.png", "image/png", new byte[]{1, 2, 3, 4}),
                new MockMultipartFile("files", "ingredients.webp", "image/webp", new byte[]{5, 6, 7, 8})
        ), "tenant-a", "user-a");

        assertThat(response.get("count")).isEqualTo(2);
        assertThat(mapList(response.get("images"))).hasSize(2).allSatisfy(asset -> {
            assertThat(asset)
                    .containsEntry("assetType", "PRODUCT_REFERENCE_IMAGE")
                    .containsEntry("referenceRole", "canonical_product_reference")
                    .containsKeys("bucket", "objectKey", "assetUrl", "contentType");
        });
        assertThat(stringList(response.get("imageUrls"))).hasSize(2);
        verify(storage, org.mockito.Mockito.times(2))
                .uploadCreatorAssetFromStream(anyString(), any(InputStream.class), anyString(), any(Duration.class));
    }

    @Test
    void rejectsUnsupportedImagesBeforeStorageIsCalled() {
        AssetStorageService storage = mock(AssetStorageService.class);
        ProductReferenceImageService service = new ProductReferenceImageService(storage);

        assertThatThrownBy(() -> service.upload(List.of(
                new MockMultipartFile("files", "product.gif", "image/gif", new byte[]{1})
        ), "tenant-a", "user-a"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("JPG, PNG, or WebP");

        verify(storage, never()).uploadCreatorAssetFromStream(anyString(), any(), anyString(), any());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mapList(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object value) {
        return value instanceof List<?> list ? (List<String>) list : List.of();
    }
}
