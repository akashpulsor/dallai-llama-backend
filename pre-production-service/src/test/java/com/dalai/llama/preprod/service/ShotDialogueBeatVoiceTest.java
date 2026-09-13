package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.entity.Shot;
import com.dalai.llama.preprod.domain.entity.ShotDialogueBeat;
import com.dalai.llama.preprod.repository.*;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class ShotDialogueBeatVoiceTest {
    private final ShotRepository shots = mock(ShotRepository.class);
    private final ShotDialogueBeatRepository beats = mock(ShotDialogueBeatRepository.class);
    private final ShotDialogueBeatService service = new ShotDialogueBeatService(shots, beats,
            mock(EffectiveSpeakerResolver.class), mock(ProjectService.class), mock(ScriptRepository.class));
    private final UUID tenantId = UUID.randomUUID();
    private final UUID shotId = UUID.randomUUID();
    private final UUID beatId = UUID.randomUUID();

    @Test
    void savesAndOverwritesVoiceIdentityOnReclone() {
        ShotDialogueBeat beat = ShotDialogueBeat.builder().id(beatId).shotId(shotId).text("Hello").build();
        when(shots.findByIdAndTenantId(shotId, tenantId)).thenReturn(Optional.of(new Shot()));
        when(beats.findByIdAndTenantId(beatId, tenantId)).thenReturn(Optional.of(beat));
        when(beats.save(any())).thenAnswer(call -> call.getArgument(0));
        assertThat(service.saveClonedVoice(tenantId, shotId, beatId, "first").clonedVoiceId()).isEqualTo("first");
        assertThat(service.saveClonedVoice(tenantId, shotId, beatId, "second").clonedVoiceId()).isEqualTo("second");
        assertThat(beat.getText()).isEqualTo("Hello");
        verify(beats, times(2)).save(beat);
    }

    @Test
    void rejectsBeatFromAnotherShot() {
        when(shots.findByIdAndTenantId(shotId, tenantId)).thenReturn(Optional.of(new Shot()));
        when(beats.findByIdAndTenantId(beatId, tenantId)).thenReturn(Optional.of(
                ShotDialogueBeat.builder().id(beatId).shotId(UUID.randomUUID()).build()));
        assertThatThrownBy(() -> service.saveClonedVoice(tenantId, shotId, beatId, "voice"))
                .isInstanceOf(PreProductionException.class);
        verify(beats, never()).save(any());
    }
}
