package com.dalai.llama.creator.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ProductReferenceImageService {

    private static final int MAX_REFERENCE_IMAGES = 8;
    private static final long MAX_IMAGE_BYTES = 15L * 1024L * 1024L;
    private static final Duration SIGNED_URL_TTL = Duration.ofDays(7);
    private static final Set<String> SUPPORTED_CONTENT_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/webp"
    );

    private final AssetStorageService assetStorageService;

    public ProductReferenceImageService(AssetStorageService assetStorageService) {
        this.assetStorageService = assetStorageService;
    }

    public Map<String, Object> upload(List<MultipartFile> files, String tenantId, String userId) {
        List<MultipartFile> safeFiles = files == null ? List.of() : files.stream().filter(file -> file != null).toList();
        if (safeFiles.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select at least one product image.");
        }
        if (safeFiles.size() > MAX_REFERENCE_IMAGES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You can upload up to 8 product images.");
        }
        safeFiles.forEach(this::validate);

        List<Map<String, Object>> images = new ArrayList<>();
        for (int index = 0; index < safeFiles.size(); index++) {
            MultipartFile file = safeFiles.get(index);
            String contentType = normalizedContentType(file);
            String objectKey = "%s/%s/product-references/%s-%s".formatted(
                    safePath(tenantId),
                    safePath(userId),
                    UUID.randomUUID(),
                    safeFilename(file.getOriginalFilename(), contentType)
            );
            AssetStorageService.StoredObject stored;
            try (InputStream inputStream = file.getInputStream()) {
                stored = assetStorageService.uploadCreatorAssetFromStream(
                        objectKey,
                        inputStream,
                        contentType,
                        SIGNED_URL_TTL
                );
            } catch (IOException ex) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read the selected product image.", ex);
            }

            Map<String, Object> image = new LinkedHashMap<>();
            image.put("id", UUID.randomUUID().toString());
            image.put("assetType", "PRODUCT_REFERENCE_IMAGE");
            image.put("assetKind", "original_product_reference");
            image.put("referenceRole", "canonical_product_reference");
            image.put("assetRole", "canonical_product_reference");
            image.put("position", index + 1);
            image.put("originalFilename", defaultString(file.getOriginalFilename(), "product-reference"));
            image.put("bucket", stored.bucket());
            image.put("objectKey", stored.objectKey());
            image.put("contentType", stored.contentType());
            image.put("sizeBytes", stored.sizeBytes());
            image.put("assetUrl", stored.signedUrl());
            image.put("signedUrl", stored.signedUrl());
            image.put("publicUrl", stored.signedUrl());
            images.add(image);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("images", images);
        response.put("assets", images);
        response.put("imageUrls", images.stream().map(image -> String.valueOf(image.get("assetUrl"))).toList());
        response.put("count", images.size());
        response.put("maxImages", MAX_REFERENCE_IMAGES);
        response.put("createdAt", OffsetDateTime.now().toString());
        return response;
    }

    private void validate(MultipartFile file) {
        if (file.isEmpty() || file.getSize() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A selected product image is empty.");
        }
        if (file.getSize() > MAX_IMAGE_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Each product image must be 15 MB or smaller.");
        }
        if (!SUPPORTED_CONTENT_TYPES.contains(normalizedContentType(file))) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Product references must be JPG, PNG, or WebP images.");
        }
    }

    private String normalizedContentType(MultipartFile file) {
        String contentType = defaultString(file.getContentType(), "").toLowerCase(Locale.ROOT);
        if ("image/jpg".equals(contentType)) {
            return "image/jpeg";
        }
        if (!contentType.isBlank()) {
            return contentType;
        }
        String filename = defaultString(file.getOriginalFilename(), "").toLowerCase(Locale.ROOT);
        if (filename.endsWith(".png")) {
            return "image/png";
        }
        if (filename.endsWith(".webp")) {
            return "image/webp";
        }
        return filename.endsWith(".jpg") || filename.endsWith(".jpeg") ? "image/jpeg" : "";
    }

    private String safeFilename(String filename, String contentType) {
        String extension = switch (contentType) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> ".jpg";
        };
        String base = defaultString(filename, "product-reference")
                .replaceFirst("(?i)\\.(jpe?g|png|webp)$", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^[.-]+|[.-]+$", "");
        return (base.isBlank() ? "product-reference" : base) + extension;
    }

    private String safePath(String value) {
        String safe = defaultString(value, "unknown")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^[.-]+|[.-]+$", "");
        return safe.isBlank() ? "unknown" : safe;
    }

    private String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
