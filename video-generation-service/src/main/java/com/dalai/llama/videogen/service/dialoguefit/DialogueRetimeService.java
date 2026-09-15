package com.dalai.llama.videogen.service.dialoguefit;

import com.dalai.llama.videogen.service.VideoGenException;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayChatRequest;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayChatRequest.LlmGatewayMessage;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayChatResponse;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Rewrites a line so it takes a given length of time to say, with its meaning intact.
 *
 * <h2>What is asked of the model, and what is not</h2>
 * It is not asked how long anything takes. That question was tried and does not work: the older
 * {@code VIDEO_DIALOGUE_FIT} prompt judged shot-01-003's line at "about eighteen" seconds when the
 * synthesized take runs 4.32 -- out by a factor of four, and any rewrite sized from that number is
 * wrong by the same factor. A text model cannot hear the voice it is writing for.
 *
 * <p>So the duration is supplied rather than requested. The line has already been synthesized and
 * measured, which turns the request into a ratio -- "make this take 60% as long" -- and a ratio is a
 * judgement about text, which is what models are actually good at. A character budget travels with
 * it as a concrete anchor, computed from this line's own seconds-per-character rather than from any
 * rule of thumb.
 *
 * <p>The model is not asked to report a duration back either. {@link Retimed#predictedSeconds} is
 * computed here, by multiplying the rewrite's length by the same measured rate. Only re-synthesizing
 * the line settles it for certain, and the UI says as much.
 *
 * <h2>It saves nothing</h2>
 * The rewrite is returned and that is all. The line is the creator's writing and the product itself,
 * so replacing it is an action they take having read the new version, not something that happens to
 * them while preparing a shot.
 *
 * <p>Errors are loud here, unlike the rest of the fit path. Elsewhere a missing measurement must
 * never stop a shot being generated -- but this runs only when someone has asked for a rewrite and
 * is waiting for one, and quietly handing back the original as though it had been rewritten would be
 * a lie about what happened.
 */
@Slf4j
@Service
public class DialogueRetimeService {

    private static final String TASK_KEY = "VIDEO_DIALOGUE_RETIME";

    private final LlmGatewayClient llmGatewayClient;
    private final ObjectMapper objectMapper;
    private final String model;

    public DialogueRetimeService(
            LlmGatewayClient llmGatewayClient,
            ObjectMapper objectMapper,
            @Value("${video-gen.dialogue-fit.model:gemini-2.5-flash}") String model
    ) {
        this.llmGatewayClient = llmGatewayClient;
        this.objectMapper = objectMapper;
        this.model = model;
    }

    /**
     * @param projectId      whose wallet the rewrite is charged to.
     * @param dialogue       the line as written.
     * @param targetSeconds  how long the rewrite should take to say -- from the fit report, already
     *                       snapped to the shot's frame grid and already allowing for the tail.
     * @param currentSeconds how long the line takes to say NOW. Measured from its synthesized take
     *                       where there is one; an estimate otherwise, which {@code rate} records.
     * @param rate           the speaking rate the budget and the prediction are computed from.
     * @param languageCode   BCP-47, or null to let the model work in the language it finds.
     */
    public Retimed retime(UUID projectId, String dialogue, double targetSeconds,
                          double currentSeconds, SpeakingRate rate, String languageCode) {
        if (dialogue == null || dialogue.isBlank()) {
            throw VideoGenException.badRequest("There is no line to rewrite");
        }
        if (targetSeconds <= 0) {
            throw VideoGenException.badRequest("A rewrite needs a target length in seconds");
        }
        String line = dialogue.trim();
        SpeakingRate effectiveRate = rate != null
                ? rate
                : SpeakingRate.fromLine(Math.max(1, line.length()), Math.max(0.001, currentSeconds));
        double knownCurrent = currentSeconds > 0 ? currentSeconds : effectiveRate.secondsFor(line);
        // The whole point of the prompt: a proportion, not a duration to be judged.
        int percentOfOriginal = (int) Math.round(100 * targetSeconds / knownCurrent);
        int charBudget = effectiveRate.charBudgetFor(targetSeconds);

        try {
            LlmGatewayChatResponse response = llmGatewayClient.chat(
                    null,
                    "dialogue-retime-" + UUID.randomUUID(),
                    new LlmGatewayChatRequest(
                            model,
                            List.of(new LlmGatewayMessage("user", "")),
                            Map.of(),
                            TASK_KEY,
                            Map.of("dialogue", line,
                                    "currentSeconds", format(knownCurrent),
                                    "targetSeconds", format(targetSeconds),
                                    "percentOfOriginal", String.valueOf(percentOfOriginal),
                                    "charBudget", String.valueOf(charBudget),
                                    "currentChars", String.valueOf(line.length()),
                                    "languageCode", languageCode == null || languageCode.isBlank()
                                            ? "the language the line is written in" : languageCode),
                            projectId));
            if (response == null || response.response() == null || response.response().isBlank()) {
                throw VideoGenException.upstream("The rewrite came back empty");
            }
            JsonNode node = objectMapper.readTree(stripFence(response.response()));
            String rewritten = node.path("rewritten").isNull() ? null : node.path("rewritten").asText(null);
            if (rewritten == null || rewritten.isBlank()) {
                throw VideoGenException.upstream("The rewrite came back without a line");
            }
            rewritten = rewritten.trim();

            // Predicted here, from the measured rate -- never taken from the model. This is the whole
            // reason the rate is threaded through: the one number that matters is arithmetic on a
            // measurement, not a judgement by something that cannot hear.
            double predicted = effectiveRate.secondsFor(rewritten);
            log.info("Retimed a line projectId={} current={}s target={}s predicted={}s"
                            + " percentAsked={}% rateSource={}",
                    projectId, format(knownCurrent), format(targetSeconds), format(predicted),
                    percentOfOriginal, effectiveRate.source());

            return new Retimed(line, rewritten,
                    node.path("direction").asText("SHORTEN"),
                    node.path("whatChanged").isNull() ? null : node.path("whatChanged").asText(null),
                    knownCurrent, targetSeconds, predicted,
                    effectiveRate.source().name(), effectiveRate.source().fromAudio(),
                    line.length(), rewritten.length(), charBudget);
        } catch (VideoGenException ex) {
            throw ex;
        } catch (Exception ex) {
            throw VideoGenException.upstream("Could not rewrite the line to length: " + ex.getMessage(), ex);
        }
    }

    private static String format(double seconds) {
        return String.format(Locale.ROOT, "%.1f", seconds);
    }

    /** Models wrap JSON in a ```json fence often enough to be worth handling rather than failing. */
    private String stripFence(String raw) {
        String trimmed = raw.strip();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstNewline = trimmed.indexOf('\n');
        int lastFence = trimmed.lastIndexOf("```");
        return firstNewline < 0 || lastFence <= firstNewline
                ? trimmed
                : trimmed.substring(firstNewline + 1, lastFence).strip();
    }

    /**
     * @param whatChanged     the model's own one-line account of what it cut or added, so the writer
     *                        can see what the rewrite cost them rather than diffing two paragraphs of
     *                        Devanagari by eye.
     * @param predictedSeconds how long the rewrite should take, computed from the measured rate --
     *                        not the model's opinion. Still a prediction: only re-dubbing settles it.
     * @param rateFromAudio   false when the rate came from the configured fallback because nothing in
     *                        the project has been synthesized yet, which makes every number here
     *                        softer and the UI says so.
     */
    public record Retimed(
            String original,
            String rewritten,
            String direction,
            String whatChanged,
            double currentSeconds,
            double targetSeconds,
            double predictedSeconds,
            String rateSource,
            boolean rateFromAudio,
            int originalChars,
            int rewrittenChars,
            int charBudget
    ) {}
}
