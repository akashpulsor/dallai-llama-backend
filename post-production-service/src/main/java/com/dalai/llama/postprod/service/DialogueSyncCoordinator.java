package com.dalai.llama.postprod.service;

import com.dalai.llama.postprod.domain.entity.DialogueSyncJob;
import com.dalai.llama.postprod.domain.entity.VoiceProfile;
import com.dalai.llama.postprod.repository.VoiceProfileRepository;
import com.dalai.llama.postprod.service.preproduction.PreProductionClient;
import com.dalai.llama.postprod.service.preproduction.PreProductionShotDetails;
import com.dalai.llama.postprod.service.videogen.VideoGenShotJob;
import com.dalai.llama.postprod.service.videogen.VideoGenerationClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The exact flow requested: get dialogue + cast details from pre-production (by project_id +
 * shot), get the already-generated shot from video-generation-service, clone the voice only if
 * the dialogue's target language differs from its source language, lip-sync the (possibly
 * dubbed) audio onto the video, and hand back the processed shot.
 *
 * <p>Deliberately NOT @Transactional -- see PostProductionJobPersistenceService's class comment.
 * Every external call here (pre-production, video-generation-service, llm-gateway x2, MinIO) is
 * blocking I/O; state commits happen via the *JobPersistenceService beans around it, not across
 * it.
 */
@Component
public class DialogueSyncCoordinator {

    private static final Logger log = LoggerFactory.getLogger(DialogueSyncCoordinator.class);

    private final PreProductionClient preProductionClient;
    private final VideoGenerationClient videoGenerationClient;
    private final VoiceProfileRepository voiceProfileRepository;
    private final VoiceCloneGenerationService voiceCloneGenerationService;
    private final VoiceSynthesisService voiceSynthesisService;
    private final LipSyncGenerationService lipSyncGenerationService;
    private final AssetPersistenceService assetPersistenceService;
    private final DialogueSyncJobPersistenceService dialogueSyncJobPersistenceService;

    public DialogueSyncCoordinator(
            PreProductionClient preProductionClient,
            VideoGenerationClient videoGenerationClient,
            VoiceProfileRepository voiceProfileRepository,
            VoiceCloneGenerationService voiceCloneGenerationService,
            VoiceSynthesisService voiceSynthesisService,
            LipSyncGenerationService lipSyncGenerationService,
            AssetPersistenceService assetPersistenceService,
            DialogueSyncJobPersistenceService dialogueSyncJobPersistenceService
    ) {
        this.preProductionClient = preProductionClient;
        this.videoGenerationClient = videoGenerationClient;
        this.voiceProfileRepository = voiceProfileRepository;
        this.voiceCloneGenerationService = voiceCloneGenerationService;
        this.voiceSynthesisService = voiceSynthesisService;
        this.lipSyncGenerationService = lipSyncGenerationService;
        this.assetPersistenceService = assetPersistenceService;
        this.dialogueSyncJobPersistenceService = dialogueSyncJobPersistenceService;
    }

    public DialogueSyncJob run(UUID tenantId, UUID projectId, UUID postProductionJobId, String shotRef,
                                UUID videoGenJobId, String targetLanguage,
                                String voiceCloneModel, String ttsModel, String lipSyncModel) {
        // 1. Dialogue + cast details from pre-production, by project_id + shot.
        PreProductionShotDetails shotDetails = preProductionClient.getShotDialogue(projectId, null, shotRef);

        // 2. The already-generated shot from video-generation-service.
        String sourceVideoUrl = videoGenerationClient.getShotVideoUrl(tenantId, videoGenJobId);

        String sourceLanguage = shotDetails.sourceDialogueLanguage();
        String effectiveTargetLanguage = (targetLanguage == null || targetLanguage.isBlank())
                ? sourceLanguage
                : targetLanguage;
        boolean sameLanguage = sourceLanguage != null
                && sourceLanguage.equalsIgnoreCase(effectiveTargetLanguage);

        DialogueSyncJob job = dialogueSyncJobPersistenceService.create(DialogueSyncJob.builder()
                .dialogueSyncJobId(UUID.randomUUID())
                .tenantId(tenantId)
                .projectId(projectId)
                .postProductionJobId(postProductionJobId)
                .shotRef(shotRef)
                .sourceLanguage(sourceLanguage)
                .targetLanguage(effectiveTargetLanguage)
                .sameLanguage(sameLanguage)
                .createdAt(OffsetDateTime.now())
                .build());

        try {
            String dialogueAudioUrl;
            if (sameLanguage) {
                // 3a. Same language: lip-sync directly against the original dialogue audio, no
                // clone/dub step -- exactly the "in case same language" branch requested.
                dialogueAudioUrl = shotDetails.referenceAudioUrl();
            } else {
                // 3b. Different language: reuse an existing clone for this character+language if
                // one exists (cloning is itself a paid call, never repeat it per shot), else
                // dispatch a new one.
                java.util.Optional<VoiceProfile> existing = voiceProfileRepository
                        .findByTenantIdAndProjectIdAndCharacterRefAndLanguage(
                                tenantId, projectId, safeCharacterRef(shotDetails), effectiveTargetLanguage);
                String newCloneJobId = null;
                VoiceProfile voiceProfile;
                if (existing.isPresent()) {
                    voiceProfile = existing.get();
                } else {
                    VoiceCloneResult cloneResult = voiceCloneGenerationService.cloneVoice(
                            tenantId, "post-prod-voiceclone-" + job.getDialogueSyncJobId(),
                            shotDetails.referenceAudioUrl(), effectiveTargetLanguage, voiceCloneModel);
                    voiceProfile = voiceProfileRepository.save(VoiceProfile.builder()
                            .voiceProfileId(UUID.randomUUID())
                            .tenantId(tenantId)
                            .projectId(projectId)
                            .characterRef(safeCharacterRef(shotDetails))
                            .language(effectiveTargetLanguage)
                            .providerId(cloneResult.providerId())
                            .providerVoiceId(cloneResult.providerVoiceId())
                            .createdAt(OffsetDateTime.now())
                            .build());
                    newCloneJobId = cloneResult.llmGatewayJobId() == null ? null : cloneResult.llmGatewayJobId().toString();
                }
                dialogueSyncJobPersistenceService.recordVoiceCloneDispatched(
                        job.getDialogueSyncJobId(), voiceProfile.getVoiceProfileId(), newCloneJobId);

                // 3c. Per-shot step: speak THIS shot's dialogue line as the cloned voice -- the
                // clone itself is just an identifier, reused across every shot the character
                // appears in; the actual audio for this specific line has to be synthesized fresh
                // each time since the line differs per shot.
                VoiceSynthesisResult synthesis = voiceSynthesisService.synthesize(
                        tenantId, "post-prod-tts-" + job.getDialogueSyncJobId(),
                        voiceProfile.getProviderVoiceId(), shotDetails.dialogueScript(), effectiveTargetLanguage, ttsModel);
                dialogueAudioUrl = synthesis.audioUrl();
            }

            // 4. Lip-sync the (possibly dubbed) audio onto the video.
            var lipSyncResult = lipSyncGenerationService.syncLips(
                    tenantId, "post-prod-lipsync-" + job.getDialogueSyncJobId(), sourceVideoUrl, dialogueAudioUrl, lipSyncModel);

            // 5. Persist our own durable copy and show the pre-processed shot.
            AssetPersistenceService.PersistedAsset asset =
                    assetPersistenceService.persist(job.getDialogueSyncJobId(), lipSyncResult.outputUri());

            return dialogueSyncJobPersistenceService.finishSuccess(
                    job.getDialogueSyncJobId(),
                    lipSyncResult.llmGatewayJobId() == null ? null : lipSyncResult.llmGatewayJobId().toString(),
                    asset.bucket(), asset.objectKey());
        } catch (RuntimeException ex) {
            log.warn("Dialogue sync failed dialogueSyncJobId={} errorMessage={}", job.getDialogueSyncJobId(), ex.getMessage());
            return dialogueSyncJobPersistenceService.finishFailure(job.getDialogueSyncJobId(), ex.getMessage());
        }
    }

    private String safeCharacterRef(PreProductionShotDetails shotDetails) {
        String character = shotDetails.character();
        return (character == null || character.isBlank())
                ? "unknown"
                : character.toLowerCase(Locale.ROOT).trim();
    }
}
