package com.dalai.llama.postprod.service;

import com.dalai.llama.postprod.domain.entity.VoiceProfile;
import com.dalai.llama.postprod.repository.VoiceProfileRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class DefaultVoicePreviewService implements VoicePreviewService {

    private static final String DEFAULT_PREVIEW_TEXT = "This is a preview of the cloned voice.";

    private final VoiceProfileRepository voiceProfileRepository;
    private final VoiceSynthesisService voiceSynthesisService;
    private final AssetPersistenceService assetPersistenceService;

    public DefaultVoicePreviewService(
            VoiceProfileRepository voiceProfileRepository,
            VoiceSynthesisService voiceSynthesisService,
            AssetPersistenceService assetPersistenceService
    ) {
        this.voiceProfileRepository = voiceProfileRepository;
        this.voiceSynthesisService = voiceSynthesisService;
        this.assetPersistenceService = assetPersistenceService;
    }

    @Override
    public VoicePreviewResult preview(UUID tenantId, UUID voiceProfileId, String text, String modelOverride) {
        VoiceProfile profile = voiceProfileRepository.findById(voiceProfileId)
                .filter(p -> p.getTenantId().equals(tenantId))
                .orElseThrow(() -> PostProductionException.notFound("Unknown voice_profile_id: " + voiceProfileId));

        String previewText = (text == null || text.isBlank()) ? DEFAULT_PREVIEW_TEXT : text;
        VoiceSynthesisResult synthesis = voiceSynthesisService.synthesize(
                tenantId, "post-prod-preview-" + UUID.randomUUID(),
                profile.getProviderVoiceId(), previewText, profile.getLanguage(), modelOverride);

        AssetPersistenceService.PersistedAsset asset = assetPersistenceService.persist(voiceProfileId, synthesis.audioUrl());
        String playableUrl = assetPersistenceService.presignedUrl(asset.bucket(), asset.objectKey());

        return new VoicePreviewResult(voiceProfileId, profile.getCharacterRef(), profile.getLanguage(), playableUrl);
    }
}
