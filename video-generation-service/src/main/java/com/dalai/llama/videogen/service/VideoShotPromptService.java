package com.dalai.llama.videogen.service;

import com.dalai.llama.videogen.dto.shotcontext.Character;
import com.dalai.llama.videogen.dto.shotcontext.MotionGraphic;
import com.dalai.llama.videogen.dto.shotcontext.ShotContext;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayChatRequest;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayChatRequest.LlmGatewayMessage;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayChatResponse;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Writes an ordinary shot's video prompt from its composed plan.
 *
 * <p>Every shot except a motion graphic had its prompt COMPOSED: a provider strategy joined the
 * shot's fields with separators. That is reliable, and it is not writing. Fifty cinematography
 * clauses concatenated -- "85mm, shallow depth of field, slow dolly in, ISO 800, shutter 180,
 * halation" -- is a spec sheet, and a video model reads it as a list of words rather than a
 * description of a shot. Everything the creator planned was present and none of it was expressed.
 *
 * <p>So the composed text is the INPUT here, not the output. It already gathers every field in the
 * shape the target provider prefers, which is work worth keeping; this asks a model to write it
 * properly, carrying the lighting, lens and movement direction through as planned and inventing
 * nothing. The anti-invention rule is the whole point: a model asked to improve a brief will add a
 * location or a camera move nobody planned, and the render will faithfully include it.
 *
 * <p>The character budget is passed so the prompt is written TO the model's real limit. A prompt
 * that fits needs no compression afterwards, and compression is a lossy pass that drops exactly
 * the specifics this exists to preserve.
 *
 * <h2>Every shot type, one path</h2>
 * Motion graphics used to have a service of their own, and the two drifted: the richer reference
 * data and the character budget were added to one and not the other, so a motion graphic went on
 * being written from four plan fields and nothing else. Which MASTER PROMPT to use is a question
 * about prompts, so it is answered where the prompts live -- this picks a task key
 * ({@code VIDEO_SHOT_PROMPT} or {@code VIDEO_MOTION_GRAPHIC_PROMPT}) and llm-gateway holds a
 * template per key. Both receive the same payload; each template reads the variables it needs.
 *
 * <h2>Failure</h2>
 * Best-effort by design: a gateway that cannot answer, or an answer that comes back empty or over
 * budget, leaves the shot with the composed prompt it would have had anyway. A shot described
 * adequately beats one that cannot be prepared at all.
 */
@Slf4j
@Service
public class VideoShotPromptService {

    private static final String TASK_KEY = "VIDEO_SHOT_PROMPT";
    private static final String MOTION_GRAPHIC_TASK_KEY = "VIDEO_MOTION_GRAPHIC_PROMPT";

    private final LlmGatewayClient llmGatewayClient;
    private final ObjectMapper objectMapper;
    private final String model;
    private final boolean enabled;

    public VideoShotPromptService(
            LlmGatewayClient llmGatewayClient,
            ObjectMapper objectMapper,
            @Value("${video-gen.shot-prompt.model:gemini-2.5-flash}") String model,
            @Value("${video-gen.shot-prompt.enabled:true}") boolean enabled
    ) {
        this.llmGatewayClient = llmGatewayClient;
        this.objectMapper = objectMapper;
        this.model = model;
        this.enabled = enabled;
    }

    /**
     * The written prompt, or null to leave the shot on its composed one.
     *
     * @param composedPrompt what the provider strategy built -- the plan, already assembled
     * @param maxChars       the target video model's real prompt limit
     */
    public String writePrompt(UUID tenantId, UUID projectId, ShotContext shotContext,
                              String composedPrompt, int maxChars) {
        if (!enabled || shotContext == null || composedPrompt == null || composedPrompt.isBlank()) {
            return null;
        }
        Integer duration = shotContext.technical() == null ? null : shotContext.technical().durationSeconds();

        try {
            LlmGatewayChatResponse response = llmGatewayClient.chat(
                    tenantId == null ? null : tenantId.toString(),
                    "video-shot-prompt-" + UUID.randomUUID(),
                    new LlmGatewayChatRequest(
                            model,
                            List.of(new LlmGatewayMessage("user", "")),
                            Map.of(),
                            shotContext.isPlannedMotionGraphic() ? MOTION_GRAPHIC_TASK_KEY : TASK_KEY,
                            templateVariables(shotContext, composedPrompt, maxChars, duration),
                            projectId));

            String written = response == null || response.response() == null
                    ? null : stripFence(response.response());
            if (written == null || written.isBlank()) {
                log.warn("Shot prompt came back empty shotRef={} -- composing the usual way",
                        shotContext.shotRef());
                return null;
            }
            // Over budget is a failure, not something to hand on: the compression pass would then
            // rewrite it, which is the lossy step this call exists to avoid. The composed prompt
            // is the honest fallback -- it goes through that same pass, as it always did.
            if (written.length() > maxChars) {
                log.warn("Written shot prompt is over budget shotRef={} chars={} maxChars={} -- composing the usual way",
                        shotContext.shotRef(), written.length(), maxChars);
                return null;
            }
            log.info("Wrote a shot prompt shotRef={} chars={} composedChars={} maxChars={}",
                    shotContext.shotRef(), written.length(), composedPrompt.length(), maxChars);
            return written;
        } catch (Exception ex) {
            log.warn("Could not write a shot prompt shotRef={} -- composing the usual way: {}",
                    shotContext.shotRef(), ex.getMessage());
            return null;
        }
    }

    /**
     * Everything both templates can read. The task key decides which of them is used, and a
     * template simply ignores the variables it does not mention -- so one payload serves both and
     * neither has to be special-cased at the call site.
     */
    private Map<String, String> templateVariables(ShotContext shotContext, String composedPrompt,
                                                  int maxChars, Integer duration) {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("composedPrompt", composedPrompt);
        variables.put("shotJson", shotJson(shotContext));
        variables.put("references", describeReferences(shotContext));
        variables.put("maxChars", String.valueOf(maxChars));
        variables.put("durationSeconds", duration == null ? "unspecified" : String.valueOf(duration));
        variables.put("fps", shotContext.technical() == null || shotContext.technical().fps() == null
                ? "unspecified" : String.valueOf(shotContext.technical().fps()));
        variables.put("shotType", orUnstated(shotContext.sceneType()));

        // The motion-graphic plan. Sent whether or not the shot has one: the shot template never
        // mentions these, so the cost of always filling them is nothing, and the alternative is a
        // branch at the call site -- which is exactly the special-casing being removed.
        MotionGraphic plan = shotContext.motionGraphic();
        variables.put("concept", plan == null ? "not stated" : orUnstated(plan.concept()));
        variables.put("visualStyle", plan == null ? "not stated" : orUnstated(plan.visualStyle()));
        // Verbatim, in its own script. It is rendered as glyphs in the frame, so anything that
        // "tidies" it changes the deliverable.
        variables.put("onScreenText", plan == null ? "not stated" : orUnstated(plan.onScreenText()));
        variables.put("animationNotes", plan == null ? "not stated" : orUnstated(plan.animationNotes()));
        return variables;
    }

    /**
     * What each attached image IS, in the order the model receives them.
     *
     * <p>The images were already being sent; nothing said what they were. A face arrived with no
     * name, so the model could not tie it to the line being spoken, and nothing distinguished "this
     * is who the person is" from "this is what the shot looks like" -- so a cast photo was as
     * likely to dictate the wardrobe and the room as the face.
     *
     * <p>Hence the per-kind instruction rather than a bare list. A cast image is identity ONLY:
     * face, build, hair. Wardrobe, location, lighting and action come from the plan, which is the
     * one place they were decided.
     */
    private String describeReferences(ShotContext shotContext) {
        List<String> lines = new ArrayList<>();
        int position = 1;

        if (shotContext.characters() != null) {
            for (Character character : shotContext.characters()) {
                if (character.faceRefObjectKey() == null) {
                    continue;
                }
                String who = character.name() == null || character.name().isBlank()
                        ? "this character" : character.name();
                String identity = character.description() == null || character.description().isBlank()
                        ? "" : " -- " + character.description();
                lines.add("  - reference image " + position++ + " = " + who + identity
                        + ". IDENTITY ONLY: take the face, build and hair from this photograph. Their"
                        + " clothing, the location, the lighting and what they are doing come from the"
                        + " shot plan, NOT from this image.");
            }
        }

        if (shotContext.productBrand() != null && shotContext.productBrand().productRefObjectKey() != null) {
            lines.add("  - reference image " + position++ + " = the product itself. Reproduce it exactly:"
                    + " shape, colour, markings and packaging are the real article, never redrawn.");
        }

        if (shotContext.referenceImages() != null) {
            for (ShotContext.ShotReferenceImage image : shotContext.referenceImages()) {
                String tag = image.tag() == null || image.tag().isBlank() ? "creator reference" : image.tag();
                lines.add("  - reference image " + position++ + " = \"" + tag + "\". Real creator-supplied"
                        + " artwork -- an app screen, a logo, a layout. Reproduce it as given; never"
                        + " redraw, restyle or invent a substitute.");
            }
        }

        if (shotContext.referenceFrames() != null && !shotContext.referenceFrames().isEmpty()) {
            lines.add("  - reference image " + position + " = this shot's own frame. What the shot"
                    + " should look like: composition, framing and staging.");
        }

        return lines.isEmpty() ? "No reference images are attached to this shot." : String.join("\n", lines);
    }

    /** The whole shot context as JSON, so the model reads named fields rather than a flattened
     * string. The composed prompt says "85mm"; this says it was lensFocalLength, which is the
     * difference between a number the model can honour deliberately and one it may drop. */
    private String shotJson(ShotContext shotContext) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(shotContext);
        } catch (Exception ex) {
            // Never fatal: the composed prompt alone is still a usable brief.
            log.warn("Could not serialise the shot context shotRef={} -- sending the composed prompt only: {}",
                    shotContext.shotRef(), ex.getMessage());
            return "(unavailable)";
        }
    }

    private static String orUnstated(String value) {
        return value == null || value.isBlank() ? "not stated" : value;
    }

    /** Models wrap prose in a fence often enough to be worth undoing; same handling as the
     * motion-graphic writer. */
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
}
