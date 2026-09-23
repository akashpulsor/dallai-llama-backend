package com.dalai.llama.preprod.service.generation;

import com.dalai.llama.preprod.domain.entity.CameraPlan;
import com.dalai.llama.preprod.domain.entity.CastProfile;
import com.dalai.llama.preprod.domain.entity.LightingPlan;
import com.dalai.llama.preprod.domain.entity.MotionGraphicPlan;
import com.dalai.llama.preprod.domain.entity.Shot;
import com.dalai.llama.preprod.domain.entity.ShotProductReference;

/**
 * Deterministic prompt assembly for the PRODUCTION/LIGHTING/CAMERA_PLAN shot image kinds --
 * mirrors creator-service's real {@code StoryboardService.buildProductionImagePrompt}/{@code
 * buildProductionSheetPrompt}: the prompt TEXT is built here, procedurally, from fields the shot
 * already carries (no separate LLM call to write the prompt itself). STORYBOARD stays on {@code
 * Shot.sketchPrompt}, generated once at shot-list time -- unchanged, not built here.
 */
public final class ShotImagePromptBuilder {

    /** Was written this elaborate ("not someone or something that merely resembles it... Preserve
     * the underlying identity and 3D structure exactly... reconstruct the same underlying subject
     * ...") to match creator-service's castIdentityInstruction depth for fal.ai's FLUX_PULID.
     * Confirmed live this is precisely what breaks Gemini: this exact wording against
     * gemini-2.5-flash-image reproducibly returned finishReason=IMAGE_OTHER (no image, no safety
     * block either -- Gemini's own finishMessage says only "the model could not generate the
     * image... try rephrasing the prompt") in 6/6 direct trials, and a system-wide audit of every
     * PRODUCTION-kind shot-image job showed a 70% empty-result rate (21/30) -- 0% for every other
     * kind (storyboard/lighting/camera_plan), which never use this instruction. A materially
     * shorter, natural-language version of the same request succeeded 4/4 in direct trials --
     * *only* when it used the subject's actual gendered pronoun; the identical wording with
     * "their" instead of "her" failed 0/5. Gemini's identity-lock image generation appears to
     * reward natural, ordinary phrasing about a real person over exhaustive technical constraints
     * -- so this stays short and concrete rather than trying to enumerate everything to preserve. */
    private static String personIdentityLockInstruction(String pronoun) {
        return "Generate a photorealistic image of this same person -- keep " + pronoun + " face, proportions, "
                + "and distinguishing features consistent with the attached reference photo, adapted naturally "
                + "to this shot's pose and camera angle.\n";
    }

    /** Same idea for a reference photo of an actual object/product rather than a person (see
     * {@link com.dalai.llama.preprod.domain.ProductReferenceClassification#CAST}) -- kept in the
     * same short, natural register as {@link #personIdentityLockInstruction} rather than the old
     * exhaustive-constraint phrasing, on the same evidence that shorter/plainer wording is what
     * Gemini actually honors here. */
    private static final String PRODUCT_IDENTITY_LOCK_INSTRUCTION =
            "Generate a photorealistic image of this same product -- keep its shape, materials, colors, and "
            + "distinguishing details consistent with the attached reference photo, adapted naturally to this "
            + "shot's framing and camera angle.\n";

    /** {@code CastProfile.gender} is free text (see its own field comment -- not a fixed enum), so
     * this only recognizes the common cases and falls back to "their" -- which is also the one
     * combination not validated to reliably work against Gemini today (untested beyond a small
     * sample; see the class-level trial notes). A known cast member's gender should always be set
     * via the cast-profile form specifically to avoid landing in that fallback. */
    private static String pronounFor(CastProfile castProfile) {
        String gender = castProfile == null || castProfile.getGender() == null ? "" : castProfile.getGender().trim().toLowerCase();
        if (gender.startsWith("f")) {
            return "her";
        }
        if (gender.startsWith("m")) {
            return "his";
        }
        return "their";
    }

    private ShotImagePromptBuilder() {
    }

    /** Photoreal finished commercial frame -- the actual image-to-video anchor. A shot-specific
     * {@code productReference} (see ShotProductReferenceService) takes priority over the shot's
     * assigned {@code castProfile} when both are present -- it's the more deliberate, per-shot
     * choice. Overload without a lighting plan for the shots that don't have one generated yet
     * (or for tests that don't want to fixture a plan). */
    public static String buildProductionPrompt(Shot shot, CastProfile castProfile, ShotProductReference productReference) {
        return buildProductionPrompt(shot, castProfile, productReference, null);
    }

    /** Full production-still prompt. Deliberately excludes:
     *  - {@code cameraMovement} -- a temporal verb ("slow push-in") has no meaning in a single
     *    still frame; the resulting perspective is carried by camera position + lens + framing
     *    instead. CAMERA_PLAN diagrams still emit the movement (they document it as a diagram);
     *    the PRODUCTION anchor omits it so we don't ask Gemini to render motion in a photo.
     *  - Every optional field that is null/blank on the Shot -- previously we emitted the literal
     *    text "not specified" for missing fields, which added ~10-15 noise lines to the prompt
     *    with no signal. Optional sections are omitted entirely when they'd be all-null.
     *
     *  Includes, when populated:
     *  - The rich {@code cine*} taxonomy on Shot (position / lens / framing / focus / image
     *    character) that the shot-list generator already fills. These are what make one
     *    "MCU at eye level with a 50mm" different from another; not passing them meant the still
     *    depended only on the same 5 flat fields for every shot in the project.
     *  - {@code expression/emotion/bodyLanguage} in every shot with people, not only cast-
     *    conditioned ones (previously gated inside the castProfile branch).
     *  - Selective slices of {@link LightingPlan} when available -- direction/quality of key/fill/
     *    rim and any motivated practical, NOT the numbered build steps or gear part numbers
     *    (those belong on the lighting-sheet image, not this still).
     */
    public static String buildProductionPrompt(Shot shot, CastProfile castProfile, ShotProductReference productReference,
                                                LightingPlan lightingPlan) {
        StringBuilder sb = new StringBuilder();
        sb.append("Create one final, production-quality advertising still that will be used as an image-to-video anchor. ")
                .append("This must look like a finished cinematic commercial frame, never a storyboard, sketch, diagram, or frame with production labels.\n\n");
        // Required fields -- always emit even if null so the prompt shape stays predictable for
        // Gemini and the tests that snapshot it. Missing values here would mean a broken shot.
        sb.append("Shot type: ").append(orNotSpecified(shot.getShotType())).append("\n");
        sb.append("What happens: ").append(orNotSpecified(shot.getAction())).append("\n");
        // shot.getScriptLine() (spoken dialogue/narration) is deliberately never included here --
        // confirmed live it's a real trigger for identity-conditioned generation failures: the
        // exact same prompt/reference photo/identity instruction failed 5/5 with a quoted
        // narrator line appended, succeeded 3/3 with it removed. It also makes no sense for a
        // still frame: a photo has no audio, and a quoted spoken line sitting next to "no
        // on-image text or labels" reads as a contradiction the model has to resolve somehow.
        //
        // cameraMovement is NOT included here on purpose -- see class doc. The still is defined
        // by the RESULTING camera state, not the motion verb that produced it.
        sb.append("Camera: ").append(orNotSpecified(shot.getCameraShotSize())).append(" shot, ")
                .append(orNotSpecified(shot.getCameraAngle())).append(" angle, ")
                .append(orNotSpecified(shot.getLensSuggestion())).append(" lens.\n");
        sb.append("Composition: ").append(orNotSpecified(shot.getComposition())).append("\n");
        sb.append("Location: ").append(orNotSpecified(shot.getLocation())).append(", ").append(orNotSpecified(shot.getTimeOfDay())).append("\n");
        sb.append("Lighting mood: ").append(orNotSpecified(shot.getLightingMood())).append("\n");

        // Rich cinematography taxonomy -- each block is skipped whole when all its fields are
        // null, so a shot without geometry data doesn't get "Geometry: ." or "Geometry: not
        // specified, not specified, ...".
        appendSection(sb, "Camera geometry", new String[][]{
                {"height", shot.getCinePositionHeight()},
                {"distance", shot.getCinePositionDistance()},
                {"lateral", shot.getCinePositionLateral()},
                {"elevation", shot.getCinePositionElevation()},
                {"orientation", shot.getCinePositionOrientation()},
        });
        appendSection(sb, "Lens", new String[][]{
                {"focal length", shot.getCineLensFocalLength()},
                {"type", shot.getCineLensType()},
                {"optical format", shot.getCineLensOpticalFormat()},
                {"distortion", shot.getCineLensDistortion()},
                {"compression", shot.getCineLensCompression()},
                {"character", shot.getCineLensCharacter()},
        });
        appendSection(sb, "Framing", new String[][]{
                {"framing", shot.getCineFraming()},
                {"subject placement", shot.getCineSubjectPlacement()},
                {"headroom", shot.getCineHeadroom()},
                {"lead room", shot.getCineLeadRoom()},
                {"visual balance", shot.getCineVisualBalance()},
        });
        appendSection(sb, "Focus", new String[][]{
                {"target", shot.getCineFocusTarget()},
                {"distance", shot.getCineFocusDistance()},
                {"depth of field", shot.getCineDepthOfField()},
        });
        appendSection(sb, "Image character", new String[][]{
                {"contrast", shot.getCineContrast()},
                {"color response", shot.getCineColorResponse()},
                {"grain", shot.getCineGrain()},
                {"halation", shot.getCineHalation()},
                {"bloom", shot.getCineBloom()},
                {"sharpness", shot.getCineSharpness()},
                {"flare", shot.getCineFlare()},
        });

        // Performance direction -- surface whether or not there's a cast profile. Previously
        // these were gated inside the identity branch, so a non-cast PRODUCTION shot never got
        // its own expression/emotion into the prompt.
        appendSection(sb, "Performance", new String[][]{
                {"expression", shot.getExpression()},
                {"emotion", shot.getEmotion()},
                {"body language", shot.getBodyLanguage()},
        });

        // Lighting plan slice -- direction/quality only, never numbered build steps or gear
        // part numbers (those belong on the lighting sheet, not the finished frame). Absent
        // plan or all-null plan -> block omitted entirely.
        if (lightingPlan != null) {
            appendSection(sb, "Lighting plan", new String[][]{
                    {"cinematic intent", lightingPlan.getCinematicIntent()},
                    {"key light", lightingPlan.getKeyLightGear()},
                    {"fill", lightingPlan.getFillLightGear()},
                    {"rim", lightingPlan.getRimLightGear()},
                    {"negative fill", lightingPlan.getNegFillGear()},
                    {"diffuser", lightingPlan.getDiffuserGear()},
            });
        }

        // Identity reference. When BOTH cast and product are present, cast leads with the
        // people-identity lock and the product rides second with its own lock -- this is
        // deliberately different from the earlier product-only-wins behavior, which silently
        // dropped the cast identity for combined shots. ShotImageService attaches both images
        // to the Gemini call in that order.
        if (productReference != null && castProfile != null) {
            appendCastIdentityBlock(sb, castProfile);
            appendProductReferenceInstruction(sb, productReference);
        } else if (productReference != null) {
            appendProductReferenceInstruction(sb, productReference);
        } else if (castProfile != null) {
            appendCastIdentityBlock(sb, castProfile);
        }

        sb.append("\nOutput: ").append(orNotSpecified(shot.getAspectRatio())).append(" composition, clean mobile-safe framing, commercial lighting, no on-image text or labels.");
        return sb.toString();
    }

    /** Shared cast-identity block extracted from the original inline branch. Only performs the
     * identity lock; performance fields (expression/emotion/body language) are appended by the
     * dedicated "Performance" section higher up so they land regardless of whether a cast
     * profile is attached. */
    private static void appendCastIdentityBlock(StringBuilder sb, CastProfile castProfile) {
        sb.append("\nPrimary subject: ").append(castProfile.getDisplayName());
        if (castProfile.getDescription() != null && !castProfile.getDescription().isBlank()) {
            sb.append(" -- ").append(castProfile.getDescription());
        }
        sb.append(". A reference photo is attached as the PRIMARY IDENTITY REFERENCE for this subject.\n")
                .append(personIdentityLockInstruction(pronounFor(castProfile)));
    }

    /** Emits "Section: k1 v1, k2 v2, ..." only for non-null/non-blank values. When every value
     * in the section is null or blank the whole section is omitted (including its label). */
    private static void appendSection(StringBuilder sb, String label, String[][] entries) {
        StringBuilder inner = new StringBuilder();
        for (String[] entry : entries) {
            String value = entry[1];
            if (value == null || value.isBlank()) continue;
            if (inner.length() > 0) inner.append(", ");
            inner.append(entry[0]).append(' ').append(value.trim());
        }
        if (inner.length() == 0) return;
        sb.append(label).append(": ").append(inner).append("\n");
    }

    /** Rookie-executable lighting build sheet: light placement, phone position, shadows, setup
     * steps -- a technical diagram, not a photoreal frame. Uses the shot's {@link LightingPlan}
     * (6 gear slots, ordered build steps) when one has been generated; falls back to the shot's
     * own flat fields otherwise so a shot without a plan yet still gets a reasonable prompt. */
    public static String buildLightingSheetPrompt(Shot shot, LightingPlan plan) {
        StringBuilder sb = new StringBuilder();
        sb.append("Professional color production planning sheet: a rookie-executable lighting build sheet, ")
                .append(orNotSpecified(shot.getAspectRatio())).append(" composition. ")
                .append("This is for one specific shot, not a generic lighting diagram -- show exact light placement, ")
                .append("subject position, phone/camera position, practical or window light sources, shadow direction, ")
                .append("and quick numbered setup steps. Render as a clear top-down map plus a perspective sketch, readable labels, ")
                .append("checklist steps, mobile-review-sized text.\n\n");
        sb.append("Subject & action (place this exact subject in the diagram, not a generic stand-in): ")
                .append(orNotSpecified(shot.getAction())).append("\n");
        if (plan != null) {
            sb.append("Cinematic intent: ").append(orNotSpecified(plan.getCinematicIntent())).append("\n");
            sb.append("Key light: ").append(orNotSpecified(plan.getKeyLightGear())).append("\n");
            sb.append("Fill light: ").append(orNotSpecified(plan.getFillLightGear())).append("\n");
            sb.append("Rim light: ").append(orNotSpecified(plan.getRimLightGear())).append("\n");
            sb.append("Negative fill: ").append(orNotSpecified(plan.getNegFillGear())).append("\n");
            sb.append("Diffuser: ").append(orNotSpecified(plan.getDiffuserGear())).append("\n");
            sb.append("Camera rig: ").append(orNotSpecified(plan.getCameraRigGear())).append("\n");
            sb.append("Setup steps:\n").append(orNotSpecified(plan.getBuildSteps())).append("\n");
        } else {
            sb.append("Lighting mood: ").append(orNotSpecified(shot.getLightingMood())).append("\n");
            sb.append("Location: ").append(orNotSpecified(shot.getLocation())).append(", ").append(orNotSpecified(shot.getTimeOfDay())).append("\n");
        }
        sb.append("Safe zone notes: ").append(orNotSpecified(shot.getSafeZoneNotes())).append("\n");
        sb.append("Do not invent lighting gear beyond what a solo smartphone creator would plausibly own.");
        return sb.toString();
    }

    /** Shoot-ready DP camera plan: position, lens, framing box, movement path, blocking. Uses the
     * shot's {@link CameraPlan} (blocking map, ordered execution steps, gimbal settings) when one
     * has been generated; falls back to the shot's own flat fields otherwise. */
    public static String buildCameraPlanSheetPrompt(Shot shot, CameraPlan plan) {
        StringBuilder sb = new StringBuilder();
        sb.append("Professional color production planning sheet: a shoot-ready camera plan sheet, ")
                .append(orNotSpecified(shot.getAspectRatio())).append(" composition. ")
                .append("This is for one specific shot -- show exact camera position, lens choice, framing box, ")
                .append("movement path (if any), subject blocking, and safe-frame notes. Render as a clear top-down map ")
                .append("plus a perspective sketch, readable labels, numbered steps, mobile-review-sized text.\n\n");
        sb.append("Subject & action (place this exact subject in the diagram, not a generic stand-in): ")
                .append(orNotSpecified(shot.getAction())).append("\n");
        sb.append("Camera: ").append(orNotSpecified(shot.getCameraShotSize())).append(" shot, ")
                .append(orNotSpecified(shot.getCameraAngle())).append(" angle, ")
                .append(orNotSpecified(shot.getCameraMovement())).append(" movement, ")
                .append(orNotSpecified(shot.getLensSuggestion())).append(" lens, ")
                .append(shot.getFps() == null ? "unspecified" : shot.getFps()).append(" fps.\n");
        sb.append("Composition: ").append(orNotSpecified(shot.getComposition())).append("\n");
        sb.append("Mobile focus area: ").append(orNotSpecified(shot.getMobileFocusArea())).append("\n");
        if (plan != null) {
            sb.append("Blocking map: ").append(orNotSpecified(plan.getBlockingMap())).append("\n");
            sb.append("Execution steps:\n").append(orNotSpecified(plan.getExecutionSteps())).append("\n");
            if (Boolean.TRUE.equals(plan.getGimbalEnabled())) {
                sb.append("Gimbal: ").append(orNotSpecified(plan.getGimbalDevice())).append(", mode ")
                        .append(orNotSpecified(plan.getGimbalMode())).append(", pan ").append(orNotSpecified(plan.getGimbalPanSpeed()))
                        .append(", tilt ").append(orNotSpecified(plan.getGimbalTiltSpeed())).append("\n");
            }
            if (plan.getSafetyFlags() != null && !plan.getSafetyFlags().isBlank()) {
                sb.append("Safety: ").append(plan.getSafetyFlags()).append("\n");
            }
        }
        sb.append("Do not invent camera gear beyond what a solo smartphone creator would plausibly own.");
        return sb.toString();
    }

    /** Preview of the on-screen graphic itself for a MOTION_GRAPHIC shot. These shots have no
     * cinematography (no lighting/camera plans), so this is their equivalent visual: a still
     * rendering of what the finished animated overlay/text/data beat will look like at the
     * halfway frame. Driven by the shot's {@link MotionGraphicPlan} (concept, on-screen text,
     * visual style, animation notes) which the planning step wrote first; falls back to the
     * shot's own fields when no plan exists yet so a manual regenerate still gets something. */
    public static String buildMotionGraphicPreviewPrompt(Shot shot, MotionGraphicPlan plan) {
        StringBuilder sb = new StringBuilder();
        sb.append("Design mockup: a single still frame preview of a motion graphic overlay for a short-form vertical video, ")
                .append(orNotSpecified(shot.getAspectRatio())).append(" composition. ")
                .append("Show what the finished animated graphic looks like at its most legible mid-frame -- text, iconography, and color ")
                .append("styling all rendered flat with clear hierarchy, mobile-review-sized type, no photorealistic subjects. ")
                .append("Do not draw storyboard sketch marks, camera-diagram arrows, or a cinematography plan -- this is the graphic itself, ")
                .append("as it will appear on screen.\n\n");
        if (plan != null) {
            sb.append("Concept: ").append(orNotSpecified(plan.getConcept())).append("\n");
            sb.append("On-screen text (render this text visibly in the frame, verbatim): ")
                    .append(orNotSpecified(plan.getOnScreenText())).append("\n");
            sb.append("Visual style: ").append(orNotSpecified(plan.getVisualStyle())).append("\n");
            sb.append("Animation notes (describe the motion in a footer caption, do not animate): ")
                    .append(orNotSpecified(plan.getAnimationNotes())).append("\n");
        } else {
            sb.append("Beat: ").append(orNotSpecified(shot.getAction())).append("\n");
            sb.append("Script line (on-screen text if applicable): ").append(orNotSpecified(shot.getScriptLine())).append("\n");
        }
        return sb.toString();
    }

    private static void appendProductReferenceInstruction(StringBuilder sb, ShotProductReference reference) {
        if (reference.getClassification() == com.dalai.llama.preprod.domain.ProductReferenceClassification.CAST) {
            boolean isPerson = reference.getPersonDescription() != null && !reference.getPersonDescription().isBlank();
            sb.append("\nA reference photo is attached as the PRIMARY IDENTITY REFERENCE.")
                    .append(isPerson ? " It shows: " + reference.getPersonDescription() + "." : "")
                    .append("\n").append(isPerson ? personIdentityLockInstruction("their") : PRODUCT_IDENTITY_LOCK_INSTRUCTION);
            return;
        }
        sb.append("\nA style reference photo is attached, marked INSPIRATION_ONLY. ");
        if (reference.getDetectedSubject() != null && !reference.getDetectedSubject().isBlank()) {
            sb.append("It shows: ").append(reference.getDetectedSubject());
            if (reference.getDominantMood() != null && !reference.getDominantMood().isBlank()) {
                sb.append(" (").append(reference.getDominantMood()).append(")");
            }
            sb.append(". ");
        }
        sb.append("Reinterpret this same visual concept, action, and energy using this project's actual subject -- ")
                .append("keep the composition, motion, and mood, but never depict the reference's own product, ingredient, or any branding shown in it.\n");
        if (Boolean.TRUE.equals(reference.getIgnoreSubject())) {
            sb.append("Ignore the photographed subject entirely -- borrow only abstract color, lighting, and composition.\n");
        } else {
            if (reference.getReferenceCameraAngle() != null && !reference.getReferenceCameraAngle().isBlank()) {
                sb.append("Camera framing to emulate: ").append(reference.getReferenceCameraAngle()).append(". ");
            }
            if (reference.getReferenceLightingStyle() != null && !reference.getReferenceLightingStyle().isBlank()) {
                sb.append("Lighting to emulate: ").append(reference.getReferenceLightingStyle()).append(". ");
            }
            if (reference.getReferenceMotion() != null && !reference.getReferenceMotion().isBlank()) {
                sb.append("Motion/energy to emulate: ").append(reference.getReferenceMotion()).append(".");
            }
            sb.append("\n");
        }
    }

    private static String orNotSpecified(Object value) {
        if (value == null) {
            return "not specified";
        }
        String text = value.toString();
        return text.isBlank() ? "not specified" : text;
    }
}
