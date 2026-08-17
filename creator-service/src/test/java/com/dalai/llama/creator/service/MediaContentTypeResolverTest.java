package com.dalai.llama.creator.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterization tests for MediaContentTypeResolver - the extraction of
 * ScreenplayVideoService's media content-type/file-extension cluster.
 */
class MediaContentTypeResolverTest {

    private final MediaContentTypeResolver resolver = new MediaContentTypeResolver();

    @Test
    void audioFileExtension_mapsKnownContentTypesAndDefaultsToMp3() {
        assertEquals("wav", resolver.audioFileExtension("audio/wav"));
        assertEquals("ogg", resolver.audioFileExtension("audio/ogg"));
        assertEquals("aac", resolver.audioFileExtension("audio/aac"));
        assertEquals("m4a", resolver.audioFileExtension("audio/mp4"));
        assertEquals("m4a", resolver.audioFileExtension("audio/m4a"));
        assertEquals("mp3", resolver.audioFileExtension("audio/mpeg"));
        assertEquals("mp3", resolver.audioFileExtension(null));
    }

    @Test
    void isImageContentType_requiresImageSlashPrefixAndKnownSubtype() {
        assertTrue(resolver.isImageContentType("image/png"));
        assertTrue(resolver.isImageContentType("image/jpeg"));
        assertTrue(resolver.isImageContentType("image/webp"));
        assertFalse(resolver.isImageContentType("video/png"));
        assertFalse(resolver.isImageContentType("image/svg+xml"));
        assertFalse(resolver.isImageContentType(null));
    }

    @Test
    void isVideoContentType_recognizesVideoPrefixAndKnownContainerHints() {
        assertTrue(resolver.isVideoContentType("video/mp4"));
        assertTrue(resolver.isVideoContentType("video/quicktime"));
        assertTrue(resolver.isVideoContentType("application/x-mpegURL-mp4"));
        assertFalse(resolver.isVideoContentType("audio/mpeg"));
        assertFalse(resolver.isVideoContentType(null));
    }

    @Test
    void videoFileExtension_mapsKnownContentTypesAndDefaultsToMp4() {
        assertEquals("mov", resolver.videoFileExtension("video/quicktime"));
        assertEquals("webm", resolver.videoFileExtension("video/webm"));
        assertEquals("mkv", resolver.videoFileExtension("video/x-matroska"));
        assertEquals("mp4", resolver.videoFileExtension("video/mp4"));
        assertEquals("mp4", resolver.videoFileExtension(null));
    }

    @Test
    void videoContentTypeForFilename_mapsKnownExtensionsAndDefaultsToEmpty() {
        assertEquals("video/quicktime", resolver.videoContentTypeForFilename("clip.mov"));
        assertEquals("video/webm", resolver.videoContentTypeForFilename("clip.WEBM"));
        assertEquals("video/x-matroska", resolver.videoContentTypeForFilename("clip.mkv"));
        assertEquals("video/x-msvideo", resolver.videoContentTypeForFilename("clip.avi"));
        assertEquals("video/mpeg", resolver.videoContentTypeForFilename("clip.mpg"));
        assertEquals("video/mp4", resolver.videoContentTypeForFilename("clip.mp4"));
        assertEquals("video/mp4", resolver.videoContentTypeForFilename("clip.m4v"));
        assertEquals("", resolver.videoContentTypeForFilename("clip.txt"));
        assertEquals("", resolver.videoContentTypeForFilename(null));
    }

    @Test
    void imageFileExtension_mapsKnownContentTypesAndDefaultsToJpg() {
        assertEquals("png", resolver.imageFileExtension("image/png"));
        assertEquals("webp", resolver.imageFileExtension("image/webp"));
        assertEquals("avif", resolver.imageFileExtension("image/avif"));
        assertEquals("gif", resolver.imageFileExtension("image/gif"));
        assertEquals("jpg", resolver.imageFileExtension("image/jpeg"));
        assertEquals("jpg", resolver.imageFileExtension(null));
    }
}
