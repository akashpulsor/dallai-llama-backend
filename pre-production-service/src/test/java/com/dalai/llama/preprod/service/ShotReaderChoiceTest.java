package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.entity.Shot;
import com.dalai.llama.preprod.dto.UpdateShotRequest;
import com.dalai.llama.preprod.repository.ShotRepository;
import com.dalai.llama.preprod.testsupport.MockedService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Who reads a shot's line is one choice: a character from the cast, or a specific built-in voice.
 * Setting one clears the other, and a blank clears both, handing the shot back to its cast.
 */
class ShotReaderChoiceTest {

    private final MockedService<ShotListGenerationService> mocked =
            MockedService.of(ShotListGenerationService.class, new ObjectMapper());
    private final UUID tenant = UUID.randomUUID();
    private final UUID shotId = UUID.randomUUID();
    private Shot shot;

    @BeforeEach
    void setUp() {
        shot = new Shot();
        shot.setId(shotId);
        shot.setTenantId(tenant);
        shot.setProjectId(UUID.randomUUID());
        ShotRepository shots = mocked.dependency(ShotRepository.class);
        when(shots.findByIdAndTenantId(shotId, tenant)).thenReturn(Optional.of(shot));
        when(shots.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    private static UpdateShotRequest reader(String dubVoiceId, String dubCastProfileId) {
        return new UpdateShotRequest(null, null, null, null, dubVoiceId, null, null, null, null,
                null, null, null, null, null, null, null, dubCastProfileId);
    }

    @Test
    void choosingACharacterReplacesABuiltInVoice() {
        shot.setDubVoiceId("voice-rachel");
        UUID ravi = UUID.randomUUID();

        mocked.instance().updateShot(tenant, shotId, reader(null, ravi.toString()));

        assertThat(shot.getDubCastProfileId()).isEqualTo(ravi);
        assertThat(shot.getDubVoiceId()).isNull();
    }

    @Test
    void choosingABuiltInVoiceReplacesACharacter() {
        shot.setDubCastProfileId(UUID.randomUUID());

        mocked.instance().updateShot(tenant, shotId, reader("voice-rachel", null));

        assertThat(shot.getDubVoiceId()).isEqualTo("voice-rachel");
        assertThat(shot.getDubCastProfileId()).isNull();
    }

    @Test
    void blanksHandTheShotBackToItsCast() {
        shot.setDubCastProfileId(UUID.randomUUID());
        shot.setDubVoiceId("voice-rachel");

        mocked.instance().updateShot(tenant, shotId, reader("", ""));

        assertThat(shot.getDubCastProfileId()).isNull();
        assertThat(shot.getDubVoiceId()).isNull();
    }

    @Test
    void anUnrelatedEditLeavesTheReaderAlone() {
        UUID ravi = UUID.randomUUID();
        shot.setDubCastProfileId(ravi);

        mocked.instance().updateShot(tenant, shotId, reader(null, null));

        assertThat(shot.getDubCastProfileId()).isEqualTo(ravi);
    }
}
