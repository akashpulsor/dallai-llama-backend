package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.entity.Shot;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The next frame edited from an earlier shot: who stays keeps their look; who is not in the shot leaves. */
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
        assertThatThrownBy(() -> StepShot.requireUsableSource(next, earlier, false)).hasMessageContaining("S1-03 has no image");
    }

    @Test
    void tellsTheModelToKeepWhoStaysAndLetTheOthersLeave() {
        String instruction = StepShot.instruction(earlier, "she turns to the window");

        assertThat(instruction)
                .contains("first attached image is shot S1-03")
                .contains("identical in face, hair, skin tone, build and wardrobe")
                .contains("Characters this shot does not include leave the frame")
                .endsWith("Also: she turns to the window");
        assertThat(StepShot.instruction(earlier, " ")).doesNotContain("Also:");
    }
}
