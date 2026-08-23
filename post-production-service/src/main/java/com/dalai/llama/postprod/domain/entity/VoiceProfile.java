package com.dalai.llama.postprod.domain.entity;

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
import java.util.UUID;

/** A cloned voice, scoped to (tenant, project, character, language) so the same character's
 * voice is cloned once and reused across every shot they appear in, not re-cloned per shot --
 * cloning is itself a paid provider call, re-doing it per shot would be pure waste. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "voice_profile")
public class VoiceProfile {

    @Id
    @Column(name = "voice_profile_id")
    private UUID voiceProfileId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    /** Whatever pre-production identifies the character/cast member by -- opaque here, this
     * service doesn't own cast identity, it just needs a stable key to reuse a clone by. */
    @Column(name = "character_ref", nullable = false, length = 128)
    private String characterRef;

    @Column(name = "language", nullable = false, length = 16)
    private String language;

    @Column(name = "provider_id", nullable = false, length = 64)
    private String providerId;

    @Column(name = "provider_voice_id", nullable = false)
    private String providerVoiceId;

    /** Our own durable copy of the reference sample this clone was made from (see
     * AssetPersistenceService) -- fal-ai/minimax/voice-clone fuses cloning and synthesis into one
     * call with no separate "reuse this voice_id" endpoint, so re-synthesizing a new line (or a
     * preview) for an already-cloned voice needs the reference audio sent again, not just
     * providerVoiceId. The URL pre-production-service originally supplied was a signed URL, not
     * durable/re-fetchable later, hence copying it here at clone time. */
    @Column(name = "reference_audio_bucket", nullable = false)
    private String referenceAudioBucket;

    @Column(name = "reference_audio_object_key", nullable = false)
    private String referenceAudioObjectKey;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
