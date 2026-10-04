package com.dalai.llama.videogen.service.generationplan;

import com.dalai.llama.videogen.domain.ReferenceKind;
import com.dalai.llama.videogen.domain.entity.ShotGenerationPlan;
import com.dalai.llama.videogen.dto.generationplan.PromptInputView;
import com.dalai.llama.videogen.dto.shotcontext.Character;
import com.dalai.llama.videogen.dto.shotcontext.Narrative;
import com.dalai.llama.videogen.dto.shotcontext.ReferenceFrame;
import com.dalai.llama.videogen.dto.shotcontext.ShotContext;
import com.dalai.llama.videogen.service.ShotContextAssemblyService;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The checklist reports what the model is actually given -- including what it is not. */
class PromptInputsTest {

    private static Map<String, PromptInputView> byKey(List<PromptInputView> inputs) {
        return inputs.stream().collect(Collectors.toMap(PromptInputView::key, Function.identity()));
    }

    private static ShotContextAssemblyService.AssembledShot assembled(ShotContext shot, String direction, UUID lightingPlan, UUID cameraPlan) {
        return new ShotContextAssemblyService.AssembledShot(shot, null, null, new ShotContextAssemblyService.ShotPromptSources(
                UUID.randomUUID(), cameraPlan, lightingPlan, null, null, OffsetDateTime.now(), List.of(), direction));
    }

    @Test
    void aFullyPlannedShotShowsEachInputWithWhatItSays() {
        ShotContext shot = mock(ShotContext.class);
        when(shot.narrative()).thenReturn(new Narrative("Ravi kneels by the sink.", "HOOK: a leak at midnight", null, "Ho gaya."));
        when(shot.referenceFrames()).thenReturn(List.of(new ReferenceFrame(ReferenceKind.STORYBOARD, "b", "k")));
        Character ravi = mock(Character.class);
        when(ravi.faceRefObjectKey()).thenReturn("faces/ravi.png");
        when(ravi.name()).thenReturn("Ravi");
        when(shot.characters()).thenReturn(List.of(ravi));
        ShotGenerationPlan plan = ShotGenerationPlan.builder().continuationFrameObjectKey("last.jpg")
                .timelineDurationSeconds(6).generationDurationSeconds(6).build();

        Map<String, PromptInputView> inputs = byKey(PromptInputs.of(assembled(shot,
                "ORIGINAL IDEA\nAPPROVED CREATIVE DIRECTION: \"Quiet Hands\" (version 2)", UUID.randomUUID(), UUID.randomUUID()), plan));

        assertThat(inputs.get("creativeDirection").present()).isTrue();
        assertThat(inputs.get("creativeDirection").detail()).isEqualTo("\"Quiet Hands\"");
        assertThat(inputs.get("script").detail()).isEqualTo("Ravi kneels by the sink.");
        assertThat(inputs.get("storyFrame").present()).isTrue();
        assertThat(inputs.get("dialogue").detail()).isEqualTo("Ho gaya.");
        assertThat(inputs.get("shotFrame").present()).isTrue();
        assertThat(inputs.get("cast").detail()).isEqualTo("Ravi");
        assertThat(inputs.get("lighting").present()).isTrue();
        assertThat(inputs.get("camera").present()).isTrue();
        assertThat(inputs.get("previousLastFrame").present()).isTrue();
        assertThat(inputs.get("timeline").present()).isTrue();
    }

    @Test
    void whatIsMissingIsSaidPlainlyIncludingTheScreenplaySceneTheBundleDoesNotCarry() {
        ShotContext shot = mock(ShotContext.class);
        when(shot.narrative()).thenReturn(new Narrative(null, null, null, null));

        Map<String, PromptInputView> inputs = byKey(PromptInputs.of(assembled(shot,
                "No creative direction has been approved for this project.", null, null), null));

        assertThat(inputs.get("creativeDirection").present()).isFalse();
        assertThat(inputs.get("script").present()).isFalse();
        assertThat(inputs.get("lighting").detail()).isEqualTo("No lighting plan for this shot.");
        assertThat(inputs.get("screenplayScene").present()).isFalse();
        assertThat(inputs.get("previousLastFrame").detail()).isEqualTo("Not attached.");
    }
}
