package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorScript;
import com.dalai.llama.creator.repository.CreatorScriptRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScreenplayVideoServiceReusableAvatarTest {

    @Test
    @SuppressWarnings("unchecked")
    void selectsApprovedCloneAndRemovesScriptSpecificMedia() {
        CreatorScriptRepository scripts = mock(CreatorScriptRepository.class);
        AssetStorageService storage = mock(AssetStorageService.class);
        ScreenplayVideoService service = service(scripts, storage);
        UUID targetId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        CreatorScript target = script(targetId, "Current screenplay", Map.of());

        Map<String, Object> sourceAsset = new LinkedHashMap<>();
        sourceAsset.put("bucket", "creator-assets");
        sourceAsset.put("objectKey", "founders/source.mp4");
        sourceAsset.put("originalFilename", "founder.mov");
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("avatarId", "avatar-1");
        profile.put("providerVoiceId", "minimax-clone-1");
        profile.put("minimaxVoiceId", "minimax-clone-1");
        profile.put("consentConfirmed", true);
        profile.put("voiceApprovalStatus", "APPROVED");
        profile.put("avatarPreviewStatus", "APPROVED");
        profile.put("sourceAsset", sourceAsset);
        profile.put("localModels", Map.of("voiceModel", "fal_minimax_voice_clone"));
        profile.put("avatarScript", "Words from the old screenplay.");
        profile.put("spokenText", "Words from the old screenplay.");
        profile.put("exactFounderAudioAsset", Map.of("objectKey", "founders/old-script.wav"));
        CreatorScript source = script(sourceId, "Source screenplay", Map.of("founderAvatarProfile", profile));

        when(scripts.findByIdAndTenantIdAndUserId(targetId, "tenant", "user")).thenReturn(Optional.of(target));
        when(scripts.findByTenantIdAndUserIdOrderByUpdatedAtDesc(eq("tenant"), eq("user"), any(Pageable.class)))
                .thenReturn(List.of(source));
        when(storage.creatorAssetsBucket()).thenReturn("creator-assets");
        when(storage.signedUrl(eq("creator-assets"), eq("founders/source.mp4"), any())).thenReturn("https://media/source.mp4");
        when(scripts.saveAndFlush(any(CreatorScript.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Map<String, Object> library = service.listReusableFounderAvatars(targetId, "tenant", "user");
        assertEquals(1, library.get("count"));

        Map<String, Object> response = service.selectReusableFounderAvatar(
                targetId,
                Map.of("sourceScriptId", sourceId.toString()),
                "tenant",
                "user"
        );
        Map<String, Object> selected = (Map<String, Object>) response.get("founderAvatarProfile");
        assertEquals("SELECTED", response.get("status"));
        assertEquals("minimax-clone-1", selected.get("providerVoiceId"));
        assertEquals("APPROVED", selected.get("voiceApprovalStatus"));
        assertTrue(Boolean.TRUE.equals(selected.get("reusableAvatarSelected")));
        assertFalse(selected.containsKey("avatarScript"));
        assertFalse(selected.containsKey("spokenText"));
        assertFalse(selected.containsKey("exactFounderAudioAsset"));

        Map<String, Object> persisted = (Map<String, Object>) target.getScriptPayload().get("founderAvatarProfile");
        assertEquals("minimax-clone-1", persisted.get("providerVoiceId"));
        assertFalse(persisted.containsKey("avatarScript"));
        assertFalse(persisted.containsKey("exactFounderAudioAsset"));
    }

    private CreatorScript script(UUID id, String title, Map<String, Object> payload) {
        return CreatorScript.builder()
                .id(id)
                .tenantId("tenant")
                .userId("user")
                .title(title)
                .durationSeconds(30)
                .scriptPayload(new LinkedHashMap<>(payload))
                .shots(List.of())
                .status("GENERATED")
                .createdAt(OffsetDateTime.now().minusMinutes(2))
                .updatedAt(OffsetDateTime.now())
                .build();
    }

    private ScreenplayVideoService service(CreatorScriptRepository scripts, AssetStorageService storage) {
        return new ScreenplayVideoService(
                scripts,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                storage,
                null,
                new ObjectMapper(),
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ZERO,
                BigDecimal.ZERO
        );
    }
}
