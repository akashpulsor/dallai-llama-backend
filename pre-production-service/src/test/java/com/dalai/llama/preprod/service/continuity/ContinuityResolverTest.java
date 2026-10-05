package com.dalai.llama.preprod.service.continuity;

import com.dalai.llama.preprod.service.continuity.ContinuityAnalysis.Candidate;
import com.dalai.llama.preprod.service.continuity.ContinuityAnalysis.FieldAnalysis;
import com.dalai.llama.preprod.service.continuity.ContinuityAnalysis.Relation;
import com.dalai.llama.preprod.service.continuity.ContinuityAnalysis.Transition;
import com.dalai.llama.preprod.service.continuity.ContinuityResolution.VisualOverride;
import com.dalai.llama.preprod.service.generation.PromptInput;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The authority order, one decision at a time. Analyses are written as the model would report them
 * -- facts only -- and the resolver must reach the decision from them on its own. */
class ContinuityResolverTest {

    private static final ContinuityResolver.Evidence TRAIN_SHOT = new ContinuityResolver.Evidence(
            "A high-speed train whizzes past a modern industrial complex", "");

    private static final Map<PromptInput, String> MIDDAY_HIGH_KEY_INPUTS = inputs(
            PromptInput.SHOT_TIME_OF_DAY, "MIDDAY",
            PromptInput.SHOT_LIGHTING_MOOD, "HIGH_KEY",
            PromptInput.LIGHTING_KEY, "A bright LED desk lamp angled to mimic harsh, directional midday sun",
            PromptInput.LIGHTING_FILL, "A white foam board lifting shadows for a high-key look",
            PromptInput.LIGHTING_DIFFUSER, "A bedsheet maintaining overall brightness");

    /** Night city -> a train passes, while every generated input says midday high-key. */
    private static ContinuityAnalysis nightCityAnalysis() {
        return new ContinuityAnalysis("Blue-hour city with lit towers and amber highway lights", List.of(
                new FieldAnalysis(VisualField.TIME_OF_DAY, "blue hour / night", null, List.of(
                        new Candidate(PromptInput.SHOT_TIME_OF_DAY, Relation.CONFLICT,
                                "The previous shot is at night and this shot does not ask for a time change.", null),
                        new Candidate(PromptInput.LIGHTING_KEY, Relation.CONFLICT, "Midday sun cannot exist at night.",
                                "Use the existing city practicals for crisp, directional highlights"))),
                new FieldAnalysis(VisualField.LIGHTING, "low-key neon: cool blue architectural light, warm amber highway light", null, List.of(
                        new Candidate(PromptInput.SHOT_LIGHTING_MOOD, Relation.CONFLICT,
                                "High-key daylight lighting conflicts with the established night environment.",
                                "Crisp separation from the existing neon and practical lights"),
                        new Candidate(PromptInput.LIGHTING_KEY, Relation.CONFLICT, "Daylight key.",
                                "Use the existing city practicals for crisp, directional highlights"),
                        new Candidate(PromptInput.LIGHTING_FILL, Relation.CONFLICT, "High-key fill.",
                                "Let the illuminated windows lift the shadows slightly"),
                        new Candidate(PromptInput.LIGHTING_DIFFUSER, Relation.COMPATIBLE, null, null))),
                new FieldAnalysis(VisualField.ARCHITECTURE, "glass towers and elevated highways", null, List.of())),
                List.of("introduce a sleek high-speed train moving horizontally through the mid-frame"),
                "The train's metal picks up cool blue reflections from the towers and warm amber from the highway.");
    }

    private static ContinuityResolution resolveNightCity(Map<VisualField, String> userOverrides) {
        return ContinuityResolver.resolve(nightCityAnalysis(), MIDDAY_HIGH_KEY_INPUTS, userOverrides, TRAIN_SHOT);
    }

    @Test
    void nightStaysNightWhenATrainEntersAndOnlyMetadataSaysMidday() {
        ContinuityResolution resolution = resolveNightCity(Map.of());

        var timeOfDay = resolution.resolvedVisualState().get(VisualField.TIME_OF_DAY);
        assertThat(timeOfDay.value()).isEqualTo("blue hour / night");
        assertThat(timeOfDay.source()).isEqualTo(ContinuitySource.REFERENCE_IMAGE);
        assertThat(timeOfDay.severity()).isEqualTo(ResolutionSeverity.OVERRIDE);
        // The shot's own time-of-day input now says what the image shows.
        assertThat(resolution.inputs().resolve(PromptInput.SHOT_TIME_OF_DAY, "MIDDAY")).isEqualTo("blue hour / night");
    }

    @Test
    void aConflictBecomesOneOverrideWithOriginalResolvedSourcesAndReason() {
        VisualOverride row = row(resolveNightCity(Map.of()), VisualField.TIME_OF_DAY);

        assertThat(row.severity()).isEqualTo(ResolutionSeverity.OVERRIDE);
        assertThat(row.originalValue()).isEqualTo("MIDDAY");
        assertThat(row.resolvedValue()).isEqualTo("blue hour / night");
        assertThat(row.originalSource()).isEqualTo(ContinuitySource.SHOT_METADATA);
        assertThat(row.resolvedSource()).isEqualTo(ContinuitySource.REFERENCE_IMAGE);
        assertThat(row.reason()).isEqualTo("The previous shot is at night and this shot does not ask for a time change.");
        assertThat(row.userOverridable()).isTrue();
    }

    @Test
    void severalLightingInputsInConflictAreOneRowListingEachOfThem() {
        ContinuityResolution resolution = resolveNightCity(Map.of());

        List<VisualOverride> lighting = resolution.overrides().stream().filter(r -> r.field() == VisualField.LIGHTING).toList();
        assertThat(lighting).hasSize(1);
        assertThat(lighting.get(0).originalValue()).isEqualTo("HIGH_KEY");
        assertThat(lighting.get(0).superseded()).extracting(ContinuityResolution.Competitor::input)
                .containsExactly(PromptInput.SHOT_LIGHTING_MOOD, PromptInput.LIGHTING_KEY, PromptInput.LIGHTING_FILL);
        // A compatible input is neither listed nor replaced.
        assertThat(resolution.substitutions()).doesNotContainKey(PromptInput.LIGHTING_DIFFUSER);
    }

    @Test
    void generatedLightingIsRestatedToFitTheReferenceNotDeleted() {
        ContinuityResolution resolution = resolveNightCity(Map.of());

        assertThat(resolution.inputs().resolve(PromptInput.LIGHTING_KEY, "raw"))
                .isEqualTo("Use the existing city practicals for crisp, directional highlights");
        assertThat(resolution.inputs().resolve(PromptInput.SHOT_LIGHTING_MOOD, "HIGH_KEY"))
                .isEqualTo("Crisp separation from the existing neon and practical lights");
    }

    @Test
    void dayStaysDayWhenANewActionArrives() {
        ContinuityAnalysis analysis = new ContinuityAnalysis(null, List.of(
                new FieldAnalysis(VisualField.TIME_OF_DAY, "bright midday", null, List.of(
                        new Candidate(PromptInput.SHOT_TIME_OF_DAY, Relation.CONFLICT, null, null)))), List.of(), null);

        ContinuityResolution resolution = ContinuityResolver.resolve(analysis, inputs(PromptInput.SHOT_TIME_OF_DAY, "NIGHT"),
                Map.of(), new ContinuityResolver.Evidence("She picks up the phone", ""));

        assertThat(resolution.resolvedVisualState().get(VisualField.TIME_OF_DAY).value()).isEqualTo("bright midday");
        assertThat(row(resolution, VisualField.TIME_OF_DAY).reason())
                .isEqualTo("This shot does not ask for a different time of day, so the previous shot's bright midday is kept.");
    }

    @Test
    void goldenHourAndTheWholeEnvironmentArePreservedThroughACameraOrLensChange() {
        ContinuityAnalysis analysis = new ContinuityAnalysis(null, List.of(
                new FieldAnalysis(VisualField.TIME_OF_DAY, "golden hour", null, List.of(
                        new Candidate(PromptInput.SHOT_TIME_OF_DAY, Relation.COMPATIBLE, null, null))),
                new FieldAnalysis(VisualField.ENVIRONMENT, "terracotta courtyard", null, List.of())), List.of(), null);

        ContinuityResolution resolution = ContinuityResolver.resolve(analysis, inputs(PromptInput.SHOT_TIME_OF_DAY, "GOLDEN_HOUR"),
                Map.of(), new ContinuityResolver.Evidence("Close-up on her hands, 85mm", ""));

        assertThat(resolution.overrides()).isEmpty();
        assertThat(resolution.substitutions()).isEmpty();
        assertThat(resolution.preserved()).containsExactly(VisualField.TIME_OF_DAY, VisualField.ENVIRONMENT);
    }

    @Test
    void anExplicitNextMorningChangesTheTimeAndIsLabelledATransitionNotAnOverride() {
        ContinuityAnalysis analysis = new ContinuityAnalysis(null, List.of(
                new FieldAnalysis(VisualField.TIME_OF_DAY, "night", new Transition("early morning", "wakes the next morning"),
                        List.of(new Candidate(PromptInput.SHOT_TIME_OF_DAY, Relation.CONFLICT, null, null)))), List.of(), null);

        ContinuityResolution resolution = ContinuityResolver.resolve(analysis, inputs(PromptInput.SHOT_TIME_OF_DAY, "DAWN"), Map.of(),
                new ContinuityResolver.Evidence("The family wakes the next morning in the same flat", ""));

        var timeOfDay = resolution.resolvedVisualState().get(VisualField.TIME_OF_DAY);
        assertThat(timeOfDay.value()).isEqualTo("early morning");
        assertThat(timeOfDay.severity()).isEqualTo(ResolutionSeverity.EXPLICIT_TRANSITION);
        VisualOverride row = row(resolution, VisualField.TIME_OF_DAY);
        assertThat(row.severity()).isEqualTo(ResolutionSeverity.EXPLICIT_TRANSITION);
        assertThat(row.originalValue()).isEqualTo("night");
        assertThat(row.resolvedSource()).isEqualTo(ContinuitySource.SHOT_DESCRIPTION);
        assertThat(row.reason()).contains("\"wakes the next morning\"");
        assertThat(resolution.inputs().resolve(PromptInput.SHOT_TIME_OF_DAY, "DAWN")).isEqualTo("early morning");
    }

    @Test
    void aStormTheScreenplaySceneCallsForChangesTheWeather() {
        ContinuityAnalysis analysis = new ContinuityAnalysis(null, List.of(
                new FieldAnalysis(VisualField.WEATHER, "clear and sunny", new Transition("thunderstorm", "a storm breaks over the village"),
                        List.of())), List.of(), null);

        ContinuityResolution resolution = ContinuityResolver.resolve(analysis, Map.of(), Map.of(),
                new ContinuityResolver.Evidence("Ravi runs for shelter", "EXT. VILLAGE - DAY. A storm breaks over the village."));

        var weather = resolution.resolvedVisualState().get(VisualField.WEATHER);
        assertThat(weather.value()).isEqualTo("thunderstorm");
        assertThat(weather.source()).isEqualTo(ContinuitySource.SCREENPLAY);
        assertThat(weather.severity()).isEqualTo(ResolutionSeverity.EXPLICIT_TRANSITION);
    }

    @Test
    void aClaimedTransitionWhoseWordsAreNotInTheShotIsIgnoredWithAWarning() {
        ContinuityAnalysis analysis = new ContinuityAnalysis(null, List.of(
                new FieldAnalysis(VisualField.TIME_OF_DAY, "night", new Transition("midday", "MIDDAY"),
                        List.of(new Candidate(PromptInput.SHOT_TIME_OF_DAY, Relation.CONFLICT, null, null)))), List.of(), null);

        ContinuityResolution resolution = ContinuityResolver.resolve(analysis, inputs(PromptInput.SHOT_TIME_OF_DAY, "MIDDAY"), Map.of(), TRAIN_SHOT);

        assertThat(resolution.resolvedVisualState().get(VisualField.TIME_OF_DAY).value()).isEqualTo("night");
        assertThat(resolution.warnings()).singleElement().asString().contains("ignored a change to \"midday\"");
    }

    @Test
    void aBrightProjectLookIsAdaptedToTheNightInsteadOfTurningItIntoDay() {
        ContinuityAnalysis analysis = new ContinuityAnalysis(null, List.of(
                new FieldAnalysis(VisualField.TIME_OF_DAY, "night", null, List.of()),
                new FieldAnalysis(VisualField.EXPOSURE, "dark, high-contrast night exposure", null, List.of(
                        new Candidate(PromptInput.PROJECT_LOOK, Relation.CONFLICT, "Bright cool daytime tones contradict the night.",
                                "Within the established night, keep the project's cool, saturated, polished modern aesthetic.")))),
                List.of(), null);

        ContinuityResolution resolution = ContinuityResolver.resolve(analysis,
                inputs(PromptInput.PROJECT_LOOK, "Colour treatment: Begins with bright, cool tones"), Map.of(), TRAIN_SHOT);

        assertThat(resolution.resolvedVisualState().get(VisualField.TIME_OF_DAY).value()).isEqualTo("night");
        assertThat(resolution.inputs().resolve(PromptInput.PROJECT_LOOK, "raw"))
                .isEqualTo("Within the established night, keep the project's cool, saturated, polished modern aesthetic.");
        assertThat(row(resolution, VisualField.EXPOSURE).originalSource()).isEqualTo(ContinuitySource.PROJECT_LOOK);
    }

    @Test
    void highKeyAgainstALowKeyReferenceIsReplacedUnlessTheShotAsksForTheChange() {
        FieldAnalysis lowKeyVersusHighKey = new FieldAnalysis(VisualField.LIGHTING, "low key", null,
                List.of(new Candidate(PromptInput.SHOT_LIGHTING_MOOD, Relation.CONFLICT, null, null)));
        ContinuityResolution kept = ContinuityResolver.resolve(new ContinuityAnalysis(null, List.of(lowKeyVersusHighKey), List.of(), null),
                inputs(PromptInput.SHOT_LIGHTING_MOOD, "HIGH_KEY"), Map.of(), TRAIN_SHOT);
        assertThat(kept.resolvedVisualState().get(VisualField.LIGHTING).value()).isEqualTo("low key");

        FieldAnalysis askedFor = new FieldAnalysis(VisualField.LIGHTING, "low key", new Transition("bright high key", "the lights come on"),
                List.of(new Candidate(PromptInput.SHOT_LIGHTING_MOOD, Relation.CONFLICT, null, null)));
        ContinuityResolution changed = ContinuityResolver.resolve(new ContinuityAnalysis(null, List.of(askedFor), List.of(), null),
                inputs(PromptInput.SHOT_LIGHTING_MOOD, "HIGH_KEY"), Map.of(),
                new ContinuityResolver.Evidence("The lights come on across the studio", ""));
        assertThat(changed.resolvedVisualState().get(VisualField.LIGHTING).value()).isEqualTo("bright high key");
    }

    @Test
    void aDifferenceThatCanCoexistIsAWarningAndReplacesNothing() {
        ContinuityAnalysis analysis = new ContinuityAnalysis(null, List.of(
                new FieldAnalysis(VisualField.COLOUR_GRADE, "muted teal grade", null, List.of(
                        new Candidate(PromptInput.SHOT_COLOR_RESPONSE, Relation.TENSION, "A saturated look can sit within the muted grade.", null)))),
                List.of(), null);

        ContinuityResolution resolution = ContinuityResolver.resolve(analysis, inputs(PromptInput.SHOT_COLOR_RESPONSE, "high saturation"),
                Map.of(), TRAIN_SHOT);

        assertThat(row(resolution, VisualField.COLOUR_GRADE).severity()).isEqualTo(ResolutionSeverity.WARNING);
        assertThat(resolution.substitutions()).isEmpty();
    }

    @Test
    void noConflictMeansNoOverrideRows() {
        ContinuityAnalysis analysis = new ContinuityAnalysis(null, List.of(
                new FieldAnalysis(VisualField.TIME_OF_DAY, "night", null, List.of(
                        new Candidate(PromptInput.SHOT_TIME_OF_DAY, Relation.COMPATIBLE, null, null)))), List.of(), null);

        ContinuityResolution resolution = ContinuityResolver.resolve(analysis, inputs(PromptInput.SHOT_TIME_OF_DAY, "NIGHT"), Map.of(), TRAIN_SHOT);

        assertThat(resolution.overrides()).isEmpty();
        assertThat(resolution.preserved()).containsExactly(VisualField.TIME_OF_DAY);
    }

    @Test
    void theUsersChoiceWinsIsShownAsADeliberateBreakAndRecomputesTheState() {
        ContinuityResolution automatic = resolveNightCity(Map.of());
        ContinuityResolution chosen = resolveNightCity(Map.of(VisualField.TIME_OF_DAY, "MIDDAY"));

        var timeOfDay = chosen.resolvedVisualState().get(VisualField.TIME_OF_DAY);
        assertThat(timeOfDay.value()).isEqualTo("MIDDAY");
        assertThat(timeOfDay.source()).isEqualTo(ContinuitySource.USER_OVERRIDE);
        VisualOverride row = row(chosen, VisualField.TIME_OF_DAY);
        assertThat(row.severity()).isEqualTo(ResolutionSeverity.USER_OVERRIDE);
        assertThat(row.originalValue()).isEqualTo("blue hour / night");
        assertThat(row.reason()).contains("continuity is deliberately broken");
        // The prompt input now carries the user's value, not continuity's.
        assertThat(chosen.inputs().resolve(PromptInput.SHOT_TIME_OF_DAY, "MIDDAY")).isEqualTo("MIDDAY");
        assertThat(automatic.inputs().resolve(PromptInput.SHOT_TIME_OF_DAY, "MIDDAY")).isEqualTo("blue hour / night");
        // Dropping the choice is the same as never having made it.
        assertThat(resolveNightCity(Map.of()).resolvedVisualState()).isEqualTo(automatic.resolvedVisualState());
    }

    @Test
    void aFieldTheImageDoesNotShowTakesTheShotsOwnValue() {
        ContinuityAnalysis analysis = new ContinuityAnalysis(null, List.of(
                new FieldAnalysis(VisualField.SEASON, null, null, List.of(
                        new Candidate(PromptInput.SHOT_LOCATION, Relation.COMPATIBLE, null, null)))), List.of(), null);

        ContinuityResolution resolution = ContinuityResolver.resolve(analysis, inputs(PromptInput.SHOT_LOCATION, "Snowy Shimla"), Map.of(), TRAIN_SHOT);

        assertThat(resolution.resolvedVisualState().get(VisualField.SEASON).severity()).isEqualTo(ResolutionSeverity.APPLIED);
        assertThat(resolution.overrides()).isEmpty();
    }

    private static VisualOverride row(ContinuityResolution resolution, VisualField field) {
        return resolution.overrides().stream().filter(r -> r.field() == field).findFirst().orElseThrow();
    }

    private static Map<PromptInput, String> inputs(Object... pairs) {
        Map<PromptInput, String> map = new EnumMap<>(PromptInput.class);
        for (int i = 0; i < pairs.length; i += 2) map.put((PromptInput) pairs[i], (String) pairs[i + 1]);
        return map;
    }
}
