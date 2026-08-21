package com.dalai.llama.creativeplanning.service.storage;

import com.dalai.llama.creativeplanning.service.CreativePlanningException;
import org.springframework.web.multipart.MultipartFile;

/** Shared multipart-upload checks -- every image upload path in this service (product reference
 * images, project-requirement attachments) validates the same way. */
public final class UploadValidation {

    private UploadValidation() {
    }

    public static void requireNonEmpty(MultipartFile file, String what) {
        if (file == null || file.isEmpty()) {
            throw CreativePlanningException.badRequest("No " + what + " uploaded");
        }
    }

    public static void requireWithinSize(MultipartFile file, long maxBytes) {
        if (file.getSize() > maxBytes) {
            throw CreativePlanningException.badRequest(
                    "Uploaded file (%d MB) exceeds the maximum of %d MB"
                            .formatted(file.getSize() / (1024 * 1024), maxBytes / (1024 * 1024)));
        }
    }

    public static String contentTypeOrDefault(MultipartFile file, String fallback) {
        return file.getContentType() != null ? file.getContentType() : fallback;
    }

    public static String extensionFor(String contentType) {
        return contentType.contains("/") ? contentType.substring(contentType.indexOf('/') + 1) : "jpg";
    }
}
