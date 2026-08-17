package com.dalai.llama.creator.service;

import java.util.Locale;

import static com.dalai.llama.creator.service.screenplayvideo.MapCoercion.*;

/**
 * Extraction of ScreenplayVideoService's media content-type/file-extension cluster - mapping
 * MIME content types to file extensions (and back, for filenames) across audio, image, and video
 * assets. Like VideoProviderCatalog, LocalAvatarModelNormalizer, and DialogueLanguageCatalog,
 * needs no ScreenplayVideoService collaborators - every method only reads its own input and
 * MapCoercion.defaultString - so this class takes no constructor arguments.
 */
final class MediaContentTypeResolver {

    String audioFileExtension(String contentType) {
        String normalized = defaultString(contentType, "").toLowerCase(Locale.ROOT);
        if (normalized.contains("wav")) {
            return "wav";
        }
        if (normalized.contains("ogg")) {
            return "ogg";
        }
        if (normalized.contains("aac")) {
            return "aac";
        }
        if (normalized.contains("mp4") || normalized.contains("m4a")) {
            return "m4a";
        }
        return "mp3";
    }

    boolean isImageContentType(String contentType) {
        String normalized = defaultString(contentType, "").toLowerCase(Locale.ROOT);
        return normalized.startsWith("image/")
                && (normalized.contains("jpeg")
                || normalized.contains("jpg")
                || normalized.contains("png")
                || normalized.contains("webp")
                || normalized.contains("avif")
                || normalized.contains("gif"));
    }

    boolean isVideoContentType(String contentType) {
        String normalized = defaultString(contentType, "").toLowerCase(Locale.ROOT);
        return normalized.startsWith("video/")
                || normalized.contains("mp4")
                || normalized.contains("quicktime")
                || normalized.contains("webm")
                || normalized.contains("x-matroska");
    }

    String videoFileExtension(String contentType) {
        String normalized = defaultString(contentType, "").toLowerCase(Locale.ROOT);
        if (normalized.contains("quicktime")) {
            return "mov";
        }
        if (normalized.contains("webm")) {
            return "webm";
        }
        if (normalized.contains("matroska")) {
            return "mkv";
        }
        return "mp4";
    }

    String videoContentTypeForFilename(String filename) {
        String normalized = defaultString(filename, "").toLowerCase(Locale.ROOT).trim();
        if (normalized.endsWith(".mov")) return "video/quicktime";
        if (normalized.endsWith(".webm")) return "video/webm";
        if (normalized.endsWith(".mkv")) return "video/x-matroska";
        if (normalized.endsWith(".avi")) return "video/x-msvideo";
        if (normalized.endsWith(".mpeg") || normalized.endsWith(".mpg")) return "video/mpeg";
        if (normalized.endsWith(".mp4") || normalized.endsWith(".m4v")) return "video/mp4";
        return "";
    }

    String imageFileExtension(String contentType) {
        String normalized = defaultString(contentType, "").toLowerCase(Locale.ROOT);
        if (normalized.contains("png")) {
            return "png";
        }
        if (normalized.contains("webp")) {
            return "webp";
        }
        if (normalized.contains("avif")) {
            return "avif";
        }
        if (normalized.contains("gif")) {
            return "gif";
        }
        return "jpg";
    }
}
