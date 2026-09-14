package com.dalai.llama.billing.domain;

import java.util.Locale;
import java.util.Map;

/**
 * Plain English for llm-gateway's task keys.
 *
 * <p>A statement line reading PRE_PROD_SHOT_LIST_GENERATE tells a creator nothing -- it is an
 * internal template name that happens to be the only record of what a charge was for. This turns
 * it into the thing that actually happened, which is what a statement line is supposed to say.
 *
 * <p>An unmapped key falls back to a readable form of itself rather than being hidden: a new task
 * key should show up as a slightly clumsy label, not vanish from someone's bill.
 */
public final class TaskLabel {

    private TaskLabel() {
    }

    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("PROJECT_REQUIREMENT_IDEA_GENERATION", "Generated project ideas"),
            Map.entry("PROJECT_IDEA_ALTERNATIVES", "Generated alternative ideas"),
            Map.entry("IDEA_CRITIQUE", "Critiqued an idea"),
            Map.entry("LOCKED_IDEA_EXTRACTION", "Locked in the chosen idea"),
            Map.entry("TREND_INTELLIGENCE_REPORT", "Built a trend report"),
            Map.entry("MARKETING_PLAN_GENERATION", "Generated the marketing plan"),
            Map.entry("MARKETING_PLAN_REVISION", "Revised the marketing plan"),
            Map.entry("MARKETING_PLAN_USER_REVISION", "Applied your marketing plan edits"),
            Map.entry("MARKETING_PLAN_CHAT", "Marketing plan chat"),
            Map.entry("MARKETING_PLAN_CRITIC_STRATEGY", "Reviewed plan strategy"),
            Map.entry("MARKETING_PLAN_CRITIC_AUDIENCE_FIT", "Reviewed plan audience fit"),
            Map.entry("MARKETING_PLAN_CRITIC_FEASIBILITY", "Reviewed plan feasibility"),
            Map.entry("PROJECT_REFERENCE_IMAGE_ANALYSIS", "Analysed a reference image"),
            Map.entry("REFERENCE_IMAGE_ANALYSIS", "Analysed a reference image"),

            Map.entry("PRE_PROD_SCRIPT_GENERATE", "Wrote the script"),
            Map.entry("PRE_PROD_SCRIPT_CRITIC", "Reviewed the script"),
            Map.entry("PRE_PROD_SCREENPLAY_GENERATE", "Wrote the screenplay"),
            Map.entry("PRE_PROD_HOOK_BEAT_PLAN_GENERATE", "Planned the hook and beats"),
            Map.entry("PRE_PROD_SHOT_LIST_GENERATE", "Broke the script into shots"),
            Map.entry("PRE_PROD_CAMERA_PLAN_GENERATE", "Planned the camera for a shot"),
            Map.entry("PRE_PROD_CAMERA_PLAN_CRITIC", "Reviewed a camera plan"),
            Map.entry("PRE_PROD_LIGHTING_PLAN_GENERATE", "Planned the lighting for a shot"),
            Map.entry("PRE_PROD_LIGHTING_PLAN_CRITIC", "Reviewed a lighting plan"),
            Map.entry("PRE_PROD_MOTION_GRAPHIC_PLAN_GENERATE", "Planned a motion graphic"),
            Map.entry("PRE_PROD_INSPIRATION_ANALYZE", "Analysed an inspiration reference"),
            Map.entry("PRE_PROD_PRODUCT_REFERENCE_CAST_DESCRIBE", "Described a product reference"),
            Map.entry("PRE_PROD_PRODUCT_REFERENCE_INSPIRATION_ANALYZE", "Analysed a product reference"),
            Map.entry("CRITIC_DIRECTOR_REVIEW", "Director's review of a shot"),
            Map.entry("CRITIC_DP_REVIEW", "Cinematographer's review of a shot"),
            Map.entry("CRITIC_PRODUCTION_DESIGN_REVIEW", "Production design review of a shot"),
            Map.entry("CRITIC_REVISION_PLAN", "Planned revisions from a review"),

            Map.entry("PRE_PROD_IMAGE_DESCRIBE", "Described a shot image"),
            Map.entry("PRE_PROD_SHOT_IMAGE_EDIT_COMPOSE", "Composed a shot image edit"),
            Map.entry("PRE_PROD_SHOT_IMAGE_IDENTITY_PROMPT_REWRITE", "Kept a character's likeness consistent"),

            Map.entry("MODEL_RECOMMENDATION", "Chose a video model for a shot"),
            Map.entry("PROMPT_COMPRESSION", "Fitted a shot prompt to the model's limit"),
            Map.entry("FOLEY_CUE_DERIVATION", "Derived the shot's sound cues"),

            Map.entry("PHONEME_GUIDE", "Worked out pronunciation for dialogue"),
            Map.entry("TRANSLATE_DIALOGUE", "Translated dialogue"),

            Map.entry("CHAT_WITH_ACTIONS", "Assistant chat")
    );

    public static String describe(String taskKey) {
        if (taskKey == null || taskKey.isBlank()) {
            return null;
        }
        String key = taskKey.trim().toUpperCase(Locale.ROOT);
        String mapped = LABELS.get(key);
        if (mapped != null) {
            return mapped;
        }
        // Unmapped: PRE_PROD_SOMETHING_NEW -> "Pre prod something new". Clumsy, but present and
        // recognisable, which beats a bill line that says nothing.
        String words = key.replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }
}
