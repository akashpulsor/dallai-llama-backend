package com.dalai.llama.videogen.service;

import com.dalai.llama.videogen.dto.shotcontext.Narrative;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.dalai.llama.videogen.service.preproduction.PreProductionViews;

/**
 * A shot's action and its script line are both narrative, and both have to reach the prompt.
 *
 * <p>Taken from shot-01-002 of a live project, where they carry different things: the action is
 * the mechanics ("the technician's hand shoves an inflated bill into Neha's view"), the script
 * line is the point of the beat ("her face clearly shows that sinking feeling of being
 * exploited"). Preferring one and dropping the other meant the staging reached the prompt while
 * what the shot was FOR did not.
 */
class NarrativeCarriesBothActionAndScriptLineTest {

    private Narrative build(String action, String scriptLine) {
        PreProductionViews.ShotView shot = mock(PreProductionViews.ShotView.class);
        when(shot.action()).thenReturn(action);
        when(shot.scriptLine()).thenReturn(scriptLine);
        when(shot.shotType()).thenReturn("ACTION");
        when(shot.voiceOver()).thenReturn(null);
        return (Narrative) ReflectionTestUtils.invokeMethod(
                new ShotContextAssemblyService(null, null),
                "buildNarrative", shot, null);
    }

    @Test
    void bothSurviveWhenTheyCarryDifferentThings() {
        Narrative narrative = build(
                "The technician's hand shoves an inflated bill into Neha's view.",
                "He presents her with an inflated bill, and her face shows that sinking feeling.");

        assertThat(narrative.scriptLine())
                .contains("shoves an inflated bill")
                .contains("that sinking feeling");
    }

    @Test
    void aScriptLineStandingInForAMissingActionIsNotRepeated() {
        Narrative narrative = build(null, "He presents her with an inflated bill.");

        assertThat(narrative.scriptLine()).isEqualTo("He presents her with an inflated bill.");
    }

    @Test
    void anActionWithNoScriptLineReadsExactlyAsBefore() {
        Narrative narrative = build("The technician shoves a bill into view.", null);

        assertThat(narrative.scriptLine()).isEqualTo("The technician shoves a bill into view.");
    }
}
