package com.dalai.llama.llmgateway.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/** A stock (non-cloned) provider voice a caller can hand straight to that provider's TTS model as
 * a {@code voice_id} -- for a character with no actor voice sample to clone (see pre-production-
 * service's {@code CastProfile.builtinVoiceId}). {@code providerVoiceId} is the provider's own
 * opaque id; {@code voiceId} is ours, stable even if the provider ever changes/renames theirs. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "builtin_voice")
public class BuiltinVoice {

    @Id
    @Column(name = "voice_id", length = 64)
    private String voiceId;

    @Column(name = "provider_id", nullable = false, length = 64)
    private String providerId;

    @Column(name = "provider_voice_id", nullable = false, length = 128)
    private String providerVoiceId;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    /** 'MALE' | 'FEMALE' -- the provider's own binary voice-gender label, unrelated to (and not
     * validated against) {@code CastProfile.gender}'s free-text field; matching the two is a UI
     * suggestion, not an enforced rule. */
    @Column(nullable = false, length = 16)
    private String gender;

    @Column(name = "preview_audio_url", columnDefinition = "text")
    private String previewAudioUrl;

    @Column(nullable = false)
    private Boolean active;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;
}
