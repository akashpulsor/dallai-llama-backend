package com.dalai.llama.videogen.service.generationplan;

import com.dalai.llama.videogen.domain.ShotActionKind;
import com.dalai.llama.videogen.domain.ShotSize;
import com.dalai.llama.videogen.dto.generationplan.RequiredActionView;
import com.dalai.llama.videogen.dto.shotcontext.Camera;
import com.dalai.llama.videogen.dto.shotcontext.DialogueBeat;
import com.dalai.llama.videogen.dto.shotcontext.MotionGraphic;
import com.dalai.llama.videogen.dto.shotcontext.Narrative;
import com.dalai.llama.videogen.dto.shotcontext.ShotContext;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The required actions are read from the plan's own sentences, so nothing can be invented, merged
 * or dropped on the way in -- every later check is only as good as this list.
 */
class RequiredActionExtractorTest {

    private final RequiredActionExtractor extractor = new RequiredActionExtractor();

    private ShotContext shot(String action, String dialogue, List<DialogueBeat> beats, Camera camera) {
        ShotContext shot = mock(ShotContext.class);
        when(shot.narrative()).thenReturn(new Narrative(action, null, null, dialogue));
        when(shot.dialogueBeats()).thenReturn(beats);
        when(shot.camera()).thenReturn(camera);
        return shot;
    }

    private static List<String> ids(List<RequiredActionView> actions) {
        return actions.stream().map(RequiredActionView::actionId).toList();
    }

    @Test
    void everyPlannedSentenceBecomesOneActionInPlanOrderBetweenTheOpeningAndFinalStates() {
        List<RequiredActionView> actions = extractor.extract(shot(
                "Ravi kneels beside the sink. He tightens the valve with a spanner. Water stops.", null, null, null));

        assertThat(ids(actions)).containsExactly("OPEN", "A1", "A2", "A3", "END");
        assertThat(actions.get(1).description()).isEqualTo("Ravi kneels beside the sink.");
        assertThat(actions.get(3).description()).isEqualTo("Water stops.");
        assertThat(actions.get(0).kind()).isEqualTo(ShotActionKind.OPENING_STATE);
        assertThat(actions.get(4).kind()).isEqualTo(ShotActionKind.ENDING_STATE);
    }

    @Test
    void eachActionDependsOnTheOneBeforeItAndTheFinalStateOnTheLastAction() {
        List<RequiredActionView> actions = extractor.extract(shot("He opens the door. He steps out.", null, null, null));

        assertThat(actions.get(1).dependsOnActionId()).isEqualTo("OPEN");
        assertThat(actions.get(2).dependsOnActionId()).isEqualTo("A1");
        assertThat(actions.get(3).dependsOnActionId()).isEqualTo("A2");
        // The final state names the action it completes, so it can never be satisfied early.
        assertThat(actions.get(3).description()).contains("A2").contains("He steps out.");
    }

    @Test
    void aRepeatedDescriptionOfTheSameActionIsCountedOnce() {
        List<RequiredActionView> actions = extractor.extract(shot(
                "She lifts the cup. She lifts the cup. She drinks.", null, null, null));

        assertThat(ids(actions)).containsExactly("OPEN", "A1", "A2", "END");
    }

    @Test
    void decimalsAndLensNumbersDoNotSplitASentence() {
        List<RequiredActionView> actions = extractor.extract(shot(
                "A slow push in on an 85mm at T2.8 over 1.5 seconds. He smiles.", null, null, null));

        assertThat(ids(actions)).containsExactly("OPEN", "A1", "A2", "END");
        assertThat(actions.get(1).description()).contains("T2.8").contains("1.5 seconds");
    }

    @Test
    void measuredDialogueIsFixedTimingAndAnUnmeasuredLineIsFlexible() {
        DialogueBeat beat = new DialogueBeat(new BigDecimal("0.5"), new BigDecimal("2.0"), "Ho gaya, aunty.",
                "ravi", null, null, null, null, null, "hi", new BigDecimal("2.4"));
        List<RequiredActionView> measured = extractor.extract(shot("He turns.", null, List.of(beat), null));
        RequiredActionView line = measured.stream().filter(a -> a.actionId().equals("D1")).findFirst().orElseThrow();
        assertThat(line.kind()).isEqualTo(ShotActionKind.DIALOGUE);
        // The measured take wins over the plan's guess: it is how long the line really takes.
        assertThat(line.fixedSeconds()).isEqualByComparingTo("2.4");
        assertThat(line.description()).contains("Ho gaya, aunty.");

        List<RequiredActionView> unmeasured = extractor.extract(shot("He turns.", "Thank you.", List.of(), null));
        assertThat(unmeasured.stream().filter(a -> a.actionId().equals("D1")).findFirst().orElseThrow().fixedSeconds()).isNull();
    }

    @Test
    void aPlannedCameraMoveIsItsOwnRequiredAction() {
        Camera camera = mock(Camera.class);
        when(camera.movementType()).thenReturn("Dolly in");
        when(camera.movementSpeed()).thenReturn("slow");
        when(camera.shotSize()).thenReturn(ShotSize.values()[0]);

        List<RequiredActionView> actions = extractor.extract(shot("He waits.", null, null, camera));

        RequiredActionView move = actions.stream().filter(a -> a.actionId().equals("C1")).findFirst().orElseThrow();
        assertThat(move.kind()).isEqualTo(ShotActionKind.CAMERA);
        assertThat(move.description()).isEqualTo("Camera: Dolly in, slow");
        assertThat(actions.get(0).description()).startsWith("Opening state at 0.0s:");
    }

    @Test
    void aShotWithNoWrittenActionStillHasItsOpeningAndFinalState() {
        List<RequiredActionView> actions = extractor.extract(shot(null, null, null, null));

        assertThat(ids(actions)).containsExactly("OPEN", "END");
        assertThat(actions.get(1).dependsOnActionId()).isEqualTo("OPEN");
    }

    @Test
    void theTextNullInPlannerFieldsIsTreatedAsNoValue() {
        Camera camera = mock(Camera.class);
        when(camera.movementType()).thenReturn("null");
        when(camera.movementTrajectory()).thenReturn("null");
        when(camera.framing()).thenReturn("null");
        when(camera.subjectPlacement()).thenReturn(" null ");

        List<RequiredActionView> actions = extractor.extract(shot("A vignette appears.", null, null, camera));

        assertThat(actions).extracting(RequiredActionView::description).noneMatch(d -> d.contains("null"));
        assertThat(actions).extracting(RequiredActionView::actionId).doesNotContain("C1");
    }

    @Test
    void aRestatedActionAndTheSpokenLineInTheActionTextAreNotExtraActions() {
        String text = "Abstract lines and geometric forms, predominantly in cool blues and grays, slowly animate and coalesce, "
                + "hinting at an incomplete architectural structure. The video opens with abstract lines and geometric forms, "
                + "hinting at an incomplete structure against a cool blue and grey background. A calm yet enthusiastic voiceover "
                + "begins, posing a thought-provoking question: \"Har smarak ke peeche, ek adrishya yojana hoti hai. "
                + "Kya azaadi bhi aisi hi thi?\"";
        DialogueBeat beat = new DialogueBeat(BigDecimal.ZERO, new BigDecimal("4"), "हर स्मारक",
                "narrator_vo", null, null, null, null, null, "hi", new BigDecimal("4"));

        List<RequiredActionView> actions = extractor.extract(shot(text, null, List.of(beat), null));

        assertThat(ids(actions)).containsExactly("OPEN", "A1", "D1", "END");
        assertThat(actions.get(1).description()).startsWith("Abstract lines");
    }

    @Test
    void withoutDialogueAQuotedSentenceStaysOneActionAndIsNotSplitAtItsQuestionMark() {
        List<RequiredActionView> actions = extractor.extract(shot(
                "She looks up and asks \"Is it ready? Now?\" while holding the cup. He nods.", null, null, null));
        assertThat(ids(actions)).containsExactly("OPEN", "A1", "A2", "END");
        assertThat(actions.get(1).description()).endsWith("holding the cup.");
    }

    @Test
    void aMotionGraphicIsPlannedFromItsAnimationNotesAndItsWordsMustAppear() {
        ShotContext graphic = shot("A sleek reveal of the AstroNext.ai app.", null, List.of(), null);
        when(graphic.isPlannedMotionGraphic()).thenReturn(true);
        when(graphic.motionGraphic()).thenReturn(new MotionGraphic("App reveal", "Your stars, decoded.", "Flat neon",
                "The logo fades in at centre. The tagline wipes in from the left. Both hold to the end."));

        List<RequiredActionView> actions = new RequiredActionExtractor().extract(graphic);

        assertThat(ids(actions)).containsExactly("OPEN", "A1", "A2", "A3", "A4", "END");
        assertThat(actions.get(1).description()).isEqualTo("The logo fades in at centre.");
        assertThat(actions.get(2).dependsOnActionId()).isEqualTo("A1");
        // The words, verbatim, on a track of their own.
        assertThat(actions.get(4).description()).isEqualTo("On-screen text appears, exactly as written: \"Your stars, decoded.\"");
        assertThat(actions.get(4).dependsOnActionId()).isEqualTo("OPEN");
        // The animation, not the text, is what the final state waits for.
        assertThat(actions.get(5).dependsOnActionId()).isEqualTo("A3");
    }

    @Test
    void aMotionGraphicWithoutAnimationNotesFallsBackToItsScriptLine() {
        ShotContext graphic = shot("Abstract lines coalesce into the logo.", null, List.of(), null);
        when(graphic.isPlannedMotionGraphic()).thenReturn(true);
        when(graphic.motionGraphic()).thenReturn(new MotionGraphic("Logo", null, null, null));

        assertThat(new RequiredActionExtractor().extract(graphic).get(1).description())
                .isEqualTo("Abstract lines coalesce into the logo.");
    }
}
