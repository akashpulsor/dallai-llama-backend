package com.dalai.llama.llmgateway.service.provider;

import com.dalai.llama.llmgateway.dto.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Request mapping for fal.ai ACE-Step (type=music), asserted against the schema fal publishes:
 * {@code tags} + {@code lyrics} + {@code duration} in seconds.
 *
 * <p>The duration cases are the ones that matter. Callers state a score length once, in seconds,
 * and both music providers are sent the same facts -- ElevenLabs reads {@code music_length_ms},
 * ACE-Step reads {@code duration}. If this adapter stopped converting, fal would silently fall
 * back to its 60-second default and every score would be the wrong length without erroring.
 * No network here: only the body builder is exercised.
 */
class FalAiMusicRequestTest {

    private final FalAiProvider provider = new FalAiProvider("http://localhost", "test-key");

    private static CanonicalRequest musicRequest(Map<String, Object> params, String message) {
        return new CanonicalRequest("fal-ai/ace-step", "music",
                message == null ? List.of() : List.of(new ChatMessage("user", message)),
                params, 180000, List.of());
    }

    @Test
    void mapsPromptToTagsBecauseAceStepSteersOnTags() {
        Map<String, Object> body = provider.toFalRequestBody(
                musicRequest(Map.of("prompt", "warm Indian cinematic score", "duration", 30.0), null));

        assertThat(body).containsEntry("prompt", "warm Indian cinematic score");
        assertThat(body).containsEntry("tags", "warm Indian cinematic score");
    }

    @Test
    void passesSecondsThroughUntouchedWhenTheCallerAlreadySpeaksSeconds() {
        Map<String, Object> body = provider.toFalRequestBody(
                musicRequest(Map.of("prompt", "score", "duration", 37.4), null));

        assertThat(body).containsEntry("duration", 37.4);
    }

    @Test
    void convertsMillisecondsToSecondsRatherThanLettingFalDefaultTo60() {
        Map<String, Object> body = provider.toFalRequestBody(
                musicRequest(Map.of("prompt", "score", "music_length_ms", 37400), null));

        assertThat(body).containsEntry("duration", 37.4);
    }

    @Test
    void stripsTheMillisecondKeyBecauseItIsElevenLabsVocabulary() {
        Map<String, Object> body = provider.toFalRequestBody(
                musicRequest(Map.of("prompt", "score", "music_length_ms", 30000, "duration", 30.0), null));

        assertThat(body).doesNotContainKey("music_length_ms");
        assertThat(body).containsEntry("duration", 30.0);
    }

    @Test
    void requestsAnInstrumentalResultByDefault() {
        // Empty lyrics is fal's documented signal for instrumental, which a background score
        // always is -- it plays under dialogue.
        Map<String, Object> body = provider.toFalRequestBody(
                musicRequest(Map.of("prompt", "score", "duration", 12.0), null));

        assertThat(body).containsEntry("lyrics", "");
    }

    @Test
    void honoursAnExplicitTagsOverrideFromTheCaller() {
        Map<String, Object> body = provider.toFalRequestBody(
                musicRequest(Map.of("prompt", "long prose prompt", "tags", "cinematic, piano, tense",
                        "duration", 20.0), null));

        assertThat(body).containsEntry("tags", "cinematic, piano, tense");
    }

    @Test
    void fallsBackToTheUserMessageWhenNoPromptParamIsGiven() {
        Map<String, Object> body = provider.toFalRequestBody(
                musicRequest(Map.of("duration", 15.0), "tense strings underscore"));

        assertThat(body).containsEntry("prompt", "tense strings underscore");
        assertThat(body).containsEntry("tags", "tense strings underscore");
    }

    @Test
    void leavesFoleyOnTheOlderAudioShapeSoTheExistingModelIsUnaffected() {
        // foley used to share this branch with music. It must keep its plain prompt body and
        // must not acquire tags/lyrics/duration.
        CanonicalRequest foley = new CanonicalRequest("beatoven/sound-effect-generation", "foley",
                List.of(new ChatMessage("user", "door slam")), Map.of(), 60000, List.of());

        Map<String, Object> body = provider.toFalRequestBody(foley);

        assertThat(body).containsEntry("prompt", "door slam");
        assertThat(body).doesNotContainKey("tags").doesNotContainKey("lyrics");
    }
}
