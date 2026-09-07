package com.dalai.llama.videogen.service.sceneenergy;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** ElevenLabs' {@code eleven_multilingual_v2} (our catalog's {@code elevenlabs-tts-v1}, see V81
 * seed) has no text-based emotion mechanism -- its only real lever is the numeric {@code
 * voice_settings} pair. Also {@link SceneEnergyStrategyResolver}'s fallback default: a TTS model
 * with no {@code emotion_delivery} capability seeded (or the capability lookup itself failing)
 * still dubs with this graceful energy default instead of erroring. */
@Component
public class NumericVoiceSettingsSceneEnergyStrategy implements SceneEnergyStrategy {

    public static final String STRATEGY_KEY = "numeric_voice_settings";

    private static final Set<String> HIGH_ENERGY = Set.of(
            "excited", "energetic", "urgent", "panicked", "thrilled", "playful", "joyful", "angry", "intense", "frantic");
    private static final Set<String> LOW_ENERGY = Set.of(
            "calm", "somber", "sad", "quiet", "serious", "reflective", "melancholic", "gentle", "solemn", "tender");

    private static final VoiceSettings HIGH = new VoiceSettings(new BigDecimal("0.3"), new BigDecimal("0.7"));
    private static final VoiceSettings LOW = new VoiceSettings(new BigDecimal("0.65"), new BigDecimal("0.25"));
    // Deliberately on the expressive side of a flat middle value -- an unrecognized/missing
    // emotion shouldn't read as stale/monotone.
    private static final VoiceSettings DEFAULT = new VoiceSettings(new BigDecimal("0.45"), new BigDecimal("0.45"));

    @Override
    public String strategyKey() {
        return STRATEGY_KEY;
    }

    @Override
    public SceneEnergyDirective apply(String emotion, String text) {
        VoiceSettings settings = forEmotion(emotion);
        return new SceneEnergyDirective(text, Map.of(
                "voice_settings", Map.of("stability", settings.stability(), "style", settings.style())));
    }

    VoiceSettings forEmotion(String emotion) {
        if (emotion == null || emotion.isBlank()) {
            return DEFAULT;
        }
        String normalized = emotion.toLowerCase(Locale.ROOT);
        if (HIGH_ENERGY.stream().anyMatch(normalized::contains)) {
            return HIGH;
        }
        if (LOW_ENERGY.stream().anyMatch(normalized::contains)) {
            return LOW;
        }
        return DEFAULT;
    }
}
