package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.entity.Shot;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The next frame edited from an earlier shot: the shot's description decides what changes, the
 * earlier image supplies the place, the light and the people who stay. */
class StepShotTest {

    private final UUID project = UUID.randomUUID();
    private final Shot earlier = shot(project, 3, "S1-03");
    private final Shot next = shot(project, 4, "S1-04");

    private static Shot shot(UUID project, int number, String ref) {
        return Shot.builder().id(UUID.randomUUID()).projectId(project).shotNumber(number).shotRef(ref).build();
    }

    @Test
    void anEarlierShotOfTheSameFilmWithAnImageCanBeSteppedFrom() {
        StepShot.requireUsableSource(next, earlier, true);
    }

    @Test
    void refusesItselfAnotherProjectAndAShotWithNoImage() {
        assertThatThrownBy(() -> StepShot.requireUsableSource(next, next, true)).hasMessageContaining("itself");
        assertThatThrownBy(() -> StepShot.requireUsableSource(next, shot(UUID.randomUUID(), 1, "X"), true))
                .hasMessageContaining("different project");
        assertThatThrownBy(() -> StepShot.requireUsableSource(next, earlier, false)).hasMessageContaining("Shot S1-03 has no image");
    }

    @Test
    void leadsWithTheEditAndLetsTheDescriptionDecideTheCamera() {
        String instruction = StepShot.instruction(earlier, "Priya", next, "Priya");

        assertThat(instruction)
                .startsWith("STEP SHOT -- EDIT THE ATTACHED IMAGE. The first attached image is shot S1-03")
                .contains("Mould that image into the shot described below")
                .contains("Who is in this frame: Priya stays")
                .contains("identical in face, hair, skin tone, build and wardrobe")
                .contains("Change to match the description: the action, poses, expressions, camera position, lens and framing")
                .endsWith("Shot to produce:");
        // The old wording told the model to keep the camera, contradicting a new camera setup.
        assertThat(instruction).doesNotContain("camera feel");
    }

    @Test
    void namesWhoStaysLeavesAndEnters() {
        assertThat(StepShot.whoIsInFrame("Priya", "Ravi", null))
                .isEqualTo("Ravi leads this frame and is not in the earlier image; Priya leaves the frame.");
        assertThat(StepShot.whoIsInFrame("Priya", null, null)).isEqualTo("Priya from the earlier image leaves the frame.");
        assertThat(StepShot.whoIsInFrame(null, "Ravi", 2))
                .isEqualTo("Ravi leads this frame and is not in the earlier image. 2 people in frame in total.");
        assertThat(StepShot.whoIsInFrame("Priya", "Priya", 0)).startsWith("no people");
        assertThat(StepShot.whoIsInFrame(null, null, null)).startsWith("neither shot has a named character");
    }

    @Test
    void namesAShotWithoutSayingShotTwice() {
        assertThat(StepShot.shotName(shot(project, 1, "shot-01-001"))).isEqualTo("shot-01-001");
        assertThat(StepShot.shotName(shot(project, 3, "S1-03"))).isEqualTo("shot S1-03");
        assertThat(StepShot.shotName(shot(project, 7, null))).isEqualTo("shot 7");
    }
}
