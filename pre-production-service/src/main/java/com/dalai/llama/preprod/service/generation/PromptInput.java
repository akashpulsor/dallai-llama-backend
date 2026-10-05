package com.dalai.llama.preprod.service.generation;

import com.dalai.llama.preprod.domain.entity.LightingPlan;
import com.dalai.llama.preprod.domain.entity.Shot;
import com.dalai.llama.preprod.dto.ApprovedCreativeDirectionContext;
import com.dalai.llama.preprod.service.continuity.ContinuitySource;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Every piece of a shot-image prompt that describes the environment (place, time, light, look),
 * keyed so it can be resolved before it is written. The builder reads these only through
 * {@link PromptInputs}; the step-shot continuity resolver reads the same raw values to find
 * conflicts with the reference image. One definition of each value serves both, so the prompt can
 * never print a value the resolver did not see.
 */
public enum PromptInput {
    SHOT_LOCATION(ContinuitySource.SHOT_METADATA, "shot location", c -> text(c.shot().getLocation())),
    SHOT_TIME_OF_DAY(ContinuitySource.SHOT_METADATA, "shot time of day", c -> text(c.shot().getTimeOfDay())),
    SHOT_LIGHTING_MOOD(ContinuitySource.SHOT_METADATA, "shot lighting mood", c -> text(c.shot().getLightingMood())),
    SHOT_CONTRAST(ContinuitySource.SHOT_METADATA, "shot contrast", c -> c.shot().getCineContrast()),
    SHOT_COLOR_RESPONSE(ContinuitySource.SHOT_METADATA, "shot colour response", c -> c.shot().getCineColorResponse()),
    SHOT_HALATION(ContinuitySource.SHOT_METADATA, "shot halation", c -> c.shot().getCineHalation()),
    SHOT_BLOOM(ContinuitySource.SHOT_METADATA, "shot bloom", c -> c.shot().getCineBloom()),
    SHOT_FLARE(ContinuitySource.SHOT_METADATA, "shot flare", c -> c.shot().getCineFlare()),
    LIGHTING_INTENT(ContinuitySource.LIGHTING_PLAN, "lighting plan intent", c -> c.plan() == null ? null : c.plan().getCinematicIntent()),
    LIGHTING_KEY(ContinuitySource.LIGHTING_PLAN, "lighting plan key light", c -> c.plan() == null ? null : c.plan().getKeyLightGear()),
    LIGHTING_FILL(ContinuitySource.LIGHTING_PLAN, "lighting plan fill light", c -> c.plan() == null ? null : c.plan().getFillLightGear()),
    LIGHTING_RIM(ContinuitySource.LIGHTING_PLAN, "lighting plan rim light", c -> c.plan() == null ? null : c.plan().getRimLightGear()),
    LIGHTING_NEG_FILL(ContinuitySource.LIGHTING_PLAN, "lighting plan negative fill", c -> c.plan() == null ? null : c.plan().getNegFillGear()),
    LIGHTING_DIFFUSER(ContinuitySource.LIGHTING_PLAN, "lighting plan diffuser", c -> c.plan() == null ? null : c.plan().getDiffuserGear()),
    PROJECT_LOOK(ContinuitySource.PROJECT_LOOK, "project look", c -> c.direction() == null ? null : c.direction().visualDirectionBlock());

    /** What the raw values are read from. */
    public record Context(Shot shot, LightingPlan plan, ApprovedCreativeDirectionContext direction) {}

    private final ContinuitySource source;
    private final String label;
    private final Function<Context, String> reader;

    PromptInput(ContinuitySource source, String label, Function<Context, String> reader) {
        this.source = source;
        this.label = label;
        this.reader = reader;
    }

    public ContinuitySource source() {
        return source;
    }

    public String label() {
        return label;
    }

    /** The value as stored, or null when the shot/plan/direction has nothing for it. */
    public String raw(Context context) {
        String value = reader.apply(context);
        return isEmpty(value) ? null : value.trim();
    }

    /** Every input that has a value, in declaration order. */
    public static Map<PromptInput, String> rawValues(Context context) {
        Map<PromptInput, String> values = new EnumMap<>(PromptInput.class);
        for (PromptInput input : values()) {
            String value = input.raw(context);
            if (value != null) values.put(input, value);
        }
        return values;
    }

    /** Blank, or a placeholder a generator writes for "nothing" ("null", "none", "n/a"). */
    public static boolean isEmpty(String value) {
        if (value == null || value.isBlank()) return true;
        String text = value.trim().toLowerCase();
        return text.equals("null") || text.equals("none") || text.equals("n/a");
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }
}
