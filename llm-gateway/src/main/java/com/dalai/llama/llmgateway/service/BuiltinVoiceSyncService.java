package com.dalai.llama.llmgateway.service;

import com.dalai.llama.llmgateway.domain.entity.BuiltinVoice;
import com.dalai.llama.llmgateway.domain.entity.BuiltinVoiceLanguage;
import com.dalai.llama.llmgateway.domain.entity.LanguageMaster;
import com.dalai.llama.llmgateway.repository.BuiltinVoiceLanguageRepository;
import com.dalai.llama.llmgateway.repository.BuiltinVoiceRepository;
import com.dalai.llama.llmgateway.repository.LanguageMasterRepository;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pulls the current ElevenLabs account's real voices (both the account's own and any shared-library
 * voices the user has added) via {@code GET /v1/voices}, keeps voices that verify {@code hi} (real
 * Hindi-native speakers, not the premade English voices trained to speak Hindi with a heavy
 * English accent) in {@code builtin_voice}, and re-wires their {@code builtin_voice_language} rows
 * to the language codes ElevenLabs itself says they speak. The pre-existing English-native seed
 * voices (Sarah/Alice/George/Brian) stay untouched -- this only inserts new rows and updates
 * language mappings, never deletes.
 *
 * <p>Fixes the user-reported "same voice comes for Hindi or English" symptom: the premade voices
 * seeded in V80 don't actually produce authentic Hindi speech even with {@code language_code=
 * hi-IN}, because they were trained on English speakers. This lets the platform pick from voices
 * ElevenLabs itself marks as verified Hindi-native.
 */
@Slf4j
@Service
public class BuiltinVoiceSyncService {

    private final BuiltinVoiceRepository builtinVoiceRepository;
    private final BuiltinVoiceLanguageRepository builtinVoiceLanguageRepository;
    private final LanguageMasterRepository languageMasterRepository;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;
    private final String apiKey;

    public BuiltinVoiceSyncService(
            BuiltinVoiceRepository builtinVoiceRepository,
            BuiltinVoiceLanguageRepository builtinVoiceLanguageRepository,
            LanguageMasterRepository languageMasterRepository,
            ObjectMapper objectMapper,
            @Value("${llm-gateway.elevenlabs.base-url}") String baseUrl,
            @Value("${llm-gateway.elevenlabs.api-key}") String apiKey
    ) {
        this.builtinVoiceRepository = builtinVoiceRepository;
        this.builtinVoiceLanguageRepository = builtinVoiceLanguageRepository;
        this.languageMasterRepository = languageMasterRepository;
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.webClient = WebClient.builder().baseUrl(baseUrl).build();
    }

    /** Refreshes {@code builtin_voice} from the ElevenLabs account. Returns the voice_ids of every
     * voice that ended up in the table with at least one Hindi language mapping after this run --
     * a Hindi-flavored dry-run readout so the caller can see whether the sync actually found real
     * Hindi voices in the account. */
    @Transactional
    public SyncResult syncFromElevenLabs() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("ELEVENLABS_API_KEY is not configured");
        }
        VoicesResponse listing = webClient.get()
                .uri("/v1/voices")
                .header("xi-api-key", apiKey)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(VoicesResponse.class)
                .timeout(Duration.ofSeconds(30))
                .block();
        if (listing == null || listing.voices() == null || listing.voices().isEmpty()) {
            return new SyncResult(0, 0, List.of(), List.of());
        }

        Set<String> knownLanguageCodes = new HashSet<>();
        for (LanguageMaster lm : languageMasterRepository.findAll()) {
            knownLanguageCodes.add(lm.getLanguageCode());
        }

        List<String> addedHindiVoiceIds = new ArrayList<>();
        List<String> updatedHindiVoiceIds = new ArrayList<>();
        int inserted = 0;
        int touched = 0;
        OffsetDateTime now = OffsetDateTime.now();
        for (ElevenLabsVoice v : listing.voices()) {
            if (v.voiceId() == null || v.voiceId().isBlank()) continue;
            // Only care about voices ElevenLabs itself marks as verified for Hindi -- premade
            // English voices "supporting" Hindi via multilingual_v2 are exactly the failure case
            // this service exists to work around.
            Set<String> verifiedCodes = extractVerifiedLanguageCodes(v);
            boolean speaksHindi = verifiedCodes.contains("hi") || verifiedCodes.contains("hi-IN") || verifiedCodes.contains("hi-Latn-IN");
            if (!speaksHindi) continue;

            // Match by provider_voice_id, not by our synthetic voice_id -- the pre-seeded voices
            // from V80 have friendly ids like "elevenlabs-sarah" while this sync would default to
            // "elevenlabs-<providerVoiceId>", so a straight-by-id lookup would insert a duplicate
            // row for the very voices that were already seeded. Provider+providerVoiceId is the
            // real natural key of "which ElevenLabs voice is this".
            BuiltinVoice existing = builtinVoiceRepository.findByProviderIdAndProviderVoiceId("elevenlabs", v.voiceId()).orElse(null);
            boolean existed = existing != null;
            BuiltinVoice row = existing != null ? existing : BuiltinVoice.builder()
                    .voiceId("elevenlabs-" + v.voiceId())
                    .providerId("elevenlabs")
                    .createdAt(now)
                    .build();
            row.setProviderVoiceId(v.voiceId());
            row.setDisplayName(displayNameFor(v));
            row.setGender(genderFor(v));
            row.setPreviewAudioUrl(v.previewUrl());
            row.setActive(true);
            row = builtinVoiceRepository.save(row);
            String ourVoiceId = row.getVoiceId();
            if (existed) touched++; else inserted++;
            if (existed) updatedHindiVoiceIds.add(ourVoiceId); else addedHindiVoiceIds.add(ourVoiceId);

            // Rewire language mappings from what ElevenLabs itself says this voice speaks, filtered
            // to codes already present in language_master (so foreign codes never break FKs).
            for (String code : verifiedCodes) {
                String normalized = normalizeLanguageCode(code, knownLanguageCodes);
                if (normalized == null) continue;
                BuiltinVoiceLanguage.Id id = new BuiltinVoiceLanguage.Id(ourVoiceId, normalized);
                if (!builtinVoiceLanguageRepository.existsById(id)) {
                    builtinVoiceLanguageRepository.save(new BuiltinVoiceLanguage(id));
                }
            }
        }

        return new SyncResult(inserted, touched, addedHindiVoiceIds, updatedHindiVoiceIds);
    }

    /** ElevenLabs' voice payload has {@code verified_languages} (a list of {language, model_id,
     * ...} objects for languages the voice is quality-checked in) and also {@code labels} (a
     * free-form label map that sometimes carries a "language" field for community voices). Read
     * both so a community-library Hindi voice that hasn't been through ElevenLabs' own verification
     * process still comes through. */
    private Set<String> extractVerifiedLanguageCodes(ElevenLabsVoice v) {
        Set<String> codes = new HashSet<>();
        if (v.verifiedLanguages() != null) {
            for (VerifiedLanguage vl : v.verifiedLanguages()) {
                if (vl.language() != null && !vl.language().isBlank()) codes.add(vl.language().toLowerCase(Locale.ROOT));
            }
        }
        if (v.labels() != null) {
            Object langLabel = v.labels().get("language");
            if (langLabel instanceof String s && !s.isBlank()) codes.add(s.toLowerCase(Locale.ROOT));
        }
        return codes;
    }

    /** Map ElevenLabs' loose language values (e.g. {@code "hi"}, {@code "en"}, {@code "hindi"}) to
     * the BCP-47 codes {@code language_master} actually holds ({@code hi-IN}, {@code en-US}, plus
     * {@code hi-Latn-IN} for romanized/Hinglish, seeded in V78). Anything we can't map to a real
     * language_master row is dropped rather than inserted as an orphan FK. */
    private String normalizeLanguageCode(String raw, Set<String> known) {
        if (raw == null) return null;
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        if (lower.isEmpty()) return null;
        String candidate = switch (lower) {
            case "hi", "hindi" -> "hi-IN";
            case "en", "english" -> "en-US";
            case "hi-latn", "hinglish", "hindi-latin" -> "hi-Latn-IN";
            default -> raw;
        };
        return known.contains(candidate) ? candidate : null;
    }

    private String displayNameFor(ElevenLabsVoice v) {
        return (v.name() == null || v.name().isBlank()) ? v.voiceId() : v.name();
    }

    private String genderFor(ElevenLabsVoice v) {
        if (v.labels() != null) {
            Object gender = v.labels().get("gender");
            if (gender instanceof String s) {
                String lower = s.trim().toLowerCase(Locale.ROOT);
                if (lower.startsWith("f")) return "FEMALE";
                if (lower.startsWith("m")) return "MALE";
            }
        }
        return "FEMALE"; // ElevenLabs' own voice objects always carry a gender label, but stay safe.
    }

    public record SyncResult(int insertedCount, int updatedCount,
                             List<String> insertedHindiVoiceIds, List<String> updatedHindiVoiceIds) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record VoicesResponse(List<ElevenLabsVoice> voices) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ElevenLabsVoice(
            @com.fasterxml.jackson.annotation.JsonProperty("voice_id") String voiceId,
            String name,
            @com.fasterxml.jackson.annotation.JsonProperty("preview_url") String previewUrl,
            java.util.Map<String, Object> labels,
            @com.fasterxml.jackson.annotation.JsonProperty("verified_languages") List<VerifiedLanguage> verifiedLanguages) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record VerifiedLanguage(String language) {
    }
}
