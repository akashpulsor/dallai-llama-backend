package com.dalai.llama.creator.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Characterization tests for FilenameSanitizer - the extraction of ScreenplayVideoService's
 * safeSlug/urlEncode string-sanitization helpers.
 */
class FilenameSanitizerTest {

    private final FilenameSanitizer sanitizer = new FilenameSanitizer();

    @Test
    void safeSlug_lowercasesAndReplacesUnsafeCharactersWithHyphens() {
        assertEquals("scene-one", sanitizer.safeSlug("Scene One!"));
        assertEquals("scene_1.mp4", sanitizer.safeSlug("scene_1.mp4"));
    }

    @Test
    void safeSlug_trimsLeadingAndTrailingHyphens() {
        assertEquals("hello", sanitizer.safeSlug("---hello---"));
    }

    @Test
    void safeSlug_defaultsToSceneForNullBlankOrFullyUnsafeInput() {
        assertEquals("scene", sanitizer.safeSlug(null));
        assertEquals("scene", sanitizer.safeSlug(""));
        assertEquals("scene", sanitizer.safeSlug("!!!"));
    }

    @Test
    void urlEncode_percentEncodesReservedCharacters() {
        assertEquals("hello+world", sanitizer.urlEncode("hello world"));
        assertEquals("a%26b", sanitizer.urlEncode("a&b"));
    }

    @Test
    void urlEncode_returnsEmptyStringForNull() {
        assertEquals("", sanitizer.urlEncode(null));
    }
}
