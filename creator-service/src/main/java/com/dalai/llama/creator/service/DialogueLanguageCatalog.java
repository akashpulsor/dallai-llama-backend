package com.dalai.llama.creator.service;

import java.util.Locale;

import static com.dalai.llama.creator.service.screenplayvideo.MapCoercion.*;

/**
 * Extraction of ScreenplayVideoService's dialogue-language normalization cluster - collapsing
 * free-text language names to a canonical form for equality comparisons, and mapping them to
 * BCP-47 locale codes for TTS/voice providers. Like VideoProviderCatalog and
 * LocalAvatarModelNormalizer, needs no ScreenplayVideoService collaborators - every method only
 * reads its own input and MapCoercion.firstText - so this class takes no constructor arguments.
 */
final class DialogueLanguageCatalog {

    boolean sameLanguage(String left, String right) {
        return normalizeLanguageName(left).equals(normalizeLanguageName(right));
    }

    private String normalizeLanguageName(String value) {
        return firstText(value)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }

    String languageCodeFor(String language) {
        return switch (normalizeLanguageName(language)) {
            case "english" -> "en-IN";
            case "hindi", "hinglish" -> "hi-IN";
            case "tamil" -> "ta-IN";
            case "telugu" -> "te-IN";
            case "bengali", "bangla" -> "bn-IN";
            case "marathi" -> "mr-IN";
            case "spanish" -> "es-ES";
            case "french" -> "fr-FR";
            case "german" -> "de-DE";
            case "portuguese" -> "pt-BR";
            case "italian" -> "it-IT";
            case "arabic" -> "ar-SA";
            case "japanese" -> "ja-JP";
            case "korean" -> "ko-KR";
            case "chinese", "mandarin", "chinese_mandarin" -> "zh-CN";
            case "indonesian" -> "id-ID";
            case "vietnamese" -> "vi-VN";
            case "thai" -> "th-TH";
            case "russian" -> "ru-RU";
            case "turkish" -> "tr-TR";
            case "dutch" -> "nl-NL";
            case "polish" -> "pl-PL";
            default -> "";
        };
    }
}
