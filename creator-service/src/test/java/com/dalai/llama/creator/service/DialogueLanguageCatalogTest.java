package com.dalai.llama.creator.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterization tests for DialogueLanguageCatalog - the extraction of
 * ScreenplayVideoService's dialogue-language normalization cluster.
 */
class DialogueLanguageCatalogTest {

    private final DialogueLanguageCatalog catalog = new DialogueLanguageCatalog();

    @Test
    void sameLanguage_isCaseAndPunctuationInsensitive() {
        assertTrue(catalog.sameLanguage("English", "english"));
        assertTrue(catalog.sameLanguage("Hindi", "hindi"));
        assertTrue(catalog.sameLanguage("  English  ", "english"));
        assertFalse(catalog.sameLanguage("English", "Hindi"));
    }

    @Test
    void sameLanguage_treatsNullAndBlankAsEqual() {
        assertTrue(catalog.sameLanguage(null, ""));
        assertTrue(catalog.sameLanguage(null, "   "));
    }

    @Test
    void languageCodeFor_mapsKnownLanguagesToBcp47Codes() {
        assertEquals("en-IN", catalog.languageCodeFor("English"));
        assertEquals("hi-IN", catalog.languageCodeFor("Hindi"));
        assertEquals("hi-IN", catalog.languageCodeFor("Hinglish"));
        assertEquals("ta-IN", catalog.languageCodeFor("Tamil"));
        assertEquals("zh-CN", catalog.languageCodeFor("Mandarin"));
        assertEquals("bn-IN", catalog.languageCodeFor("Bangla"));
    }

    @Test
    void languageCodeFor_returnsEmptyForUnknownOrBlankLanguage() {
        assertEquals("", catalog.languageCodeFor("Klingon"));
        assertEquals("", catalog.languageCodeFor(null));
        assertEquals("", catalog.languageCodeFor(""));
    }
}
