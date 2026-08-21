package com.dalai.llama.postprod.service;

import com.dalai.llama.postprod.domain.entity.DubbingJob;
import com.dalai.llama.postprod.dto.DubbingJobView;
import com.dalai.llama.postprod.repository.DubbingJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The standalone flow: upload a video, transcribe it, translate the dialogue, clone the voice,
 * synthesize the translated line, dub it back onto the video. Reuses every provider-dispatch
 * service already built for the shot-based pipeline (voice-clone, TTS, lip-sync) -- only the
 * source (an upload, not a video-generation-service shot) and the dialogue source (transcription
 * + translation, not pre-production-service) differ.
 *
 * <p><b>Named v1 simplification, not a silent gap:</b> the whole video is treated as one unit --
 * one transcript, one translation, one cloned voice, one synthesized audio track, one lip-sync
 * call. True minute-by-minute segmentation (more accurate for long videos, allows resuming/
 * reviewing per-segment) would need each segment transcribed/translated/synthesized/lip-synced
 * separately and then the resulting clips stitched back together in order -- that stitching step
 * needs real video-processing infrastructure (ffmpeg or equivalent) this codebase doesn't have
 * yet, so it's a real, bounded follow-up, not something silently approximated here. This also
 * means provider-side duration/size limits for transcription/lip-sync (unverified, per
 * FalAiProvider's own caveat) apply to the FULL video length, not a bounded per-minute chunk.
 *
 * <p>Deliberately NOT @Transactional -- see PostProductionJobPersistenceService's class comment.
 */
@Service
public class DubbingOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(DubbingOrchestrator.class);

    private final SourceUploadService sourceUploadService;
    private final DubbingJobRepository dubbingJobRepository;
    private final DubbingJobPersistenceService jobPersistenceService;
    private final TranscriptionService transcriptionService;
    private final TranslationService translationService;
    private final VoiceCloneGenerationService voiceCloneGenerationService;
    private final VoiceSynthesisService voiceSynthesisService;
    private final LipSyncGenerationService lipSyncGenerationService;
    private final AssetPersistenceService assetPersistenceService;

    public DubbingOrchestrator(
            SourceUploadService sourceUploadService,
            DubbingJobRepository dubbingJobRepository,
            DubbingJobPersistenceService jobPersistenceService,
            TranscriptionService transcriptionService,
            TranslationService translationService,
            VoiceCloneGenerationService voiceCloneGenerationService,
            VoiceSynthesisService voiceSynthesisService,
            LipSyncGenerationService lipSyncGenerationService,
            AssetPersistenceService assetPersistenceService
    ) {
        this.sourceUploadService = sourceUploadService;
        this.dubbingJobRepository = dubbingJobRepository;
        this.jobPersistenceService = jobPersistenceService;
        this.transcriptionService = transcriptionService;
        this.translationService = translationService;
        this.voiceCloneGenerationService = voiceCloneGenerationService;
        this.voiceSynthesisService = voiceSynthesisService;
        this.lipSyncGenerationService = lipSyncGenerationService;
        this.assetPersistenceService = assetPersistenceService;
    }

    public DubbingJobView dub(UUID tenantId, UUID userId, MultipartFile file, String targetLanguage) {
        UUID jobId = UUID.randomUUID();
        AssetPersistenceService.PersistedAsset source = sourceUploadService.upload(jobId, file);
        String sourceVideoUrl = assetPersistenceService.presignedUrl(source.bucket(), source.objectKey());

        DubbingJob job = jobPersistenceService.create(DubbingJob.builder()
                .jobId(jobId)
                .tenantId(tenantId)
                .createdBy(userId)
                .sourceBucket(source.bucket())
                .sourceObjectKey(source.objectKey())
                .targetLanguage(targetLanguage)
                .createdAt(OffsetDateTime.now())
                .build());

        try {
            TranscriptionResult transcription = transcriptionService.transcribe(
                    tenantId, "dubbing-transcribe-" + jobId, sourceVideoUrl, null);
            // Source language isn't independently known -- the transcription model doesn't
            // report it back distinctly from the transcript text itself in this best-effort
            // shape (see FalAiProvider's own caveat). Treated as unknown/auto rather than
            // guessed; the translation prompt's own {{sourceLanguage}} variable degrades to
            // "auto-detect from the text" when this is blank.
            job = jobPersistenceService.recordTranscript(jobId, null, transcription.transcript());

            String translated = translationService.translate(
                    tenantId.toString(), "dubbing-translate-" + jobId,
                    transcription.transcript(), job.getSourceLanguage(), targetLanguage);
            job = jobPersistenceService.recordTranslation(jobId, translated);

            VoiceCloneResult cloneResult = voiceCloneGenerationService.cloneVoice(
                    tenantId, "dubbing-voiceclone-" + jobId, sourceVideoUrl, targetLanguage, null);

            VoiceSynthesisResult synthesis = voiceSynthesisService.synthesize(
                    tenantId, "dubbing-tts-" + jobId,
                    cloneResult.providerVoiceId(), translated, targetLanguage, null);

            var lipSyncResult = lipSyncGenerationService.syncLips(
                    tenantId, "dubbing-lipsync-" + jobId, sourceVideoUrl, synthesis.audioUrl(), null);

            AssetPersistenceService.PersistedAsset output = assetPersistenceService.persist(jobId, lipSyncResult.outputUri());
            return toView(jobPersistenceService.finishSuccess(jobId, output.bucket(), output.objectKey()));
        } catch (RuntimeException ex) {
            log.warn("Dubbing job failed jobId={} errorMessage={}", jobId, ex.getMessage());
            return toView(jobPersistenceService.finishFailure(jobId, ex.getMessage()));
        }
    }

    public DubbingJobView getJob(UUID tenantId, UUID jobId) {
        DubbingJob job = dubbingJobRepository.findById(jobId)
                .filter(j -> j.getTenantId().equals(tenantId))
                .orElseThrow(() -> PostProductionException.notFound("Unknown job_id: " + jobId));
        return toView(job);
    }

    public String getVideoUrl(UUID tenantId, UUID jobId) {
        DubbingJob job = dubbingJobRepository.findById(jobId)
                .filter(j -> j.getTenantId().equals(tenantId))
                .orElseThrow(() -> PostProductionException.notFound("Unknown job_id: " + jobId));
        if (job.getOutputBucket() == null || job.getOutputObjectKey() == null) {
            throw PostProductionException.notFound(
                    "job_id=%s has no persisted dubbed video yet (status=%s)".formatted(jobId, job.getStatus()));
        }
        return assetPersistenceService.presignedUrl(job.getOutputBucket(), job.getOutputObjectKey());
    }

    private DubbingJobView toView(DubbingJob job) {
        return new DubbingJobView(
                job.getJobId(), job.getTargetLanguage(), job.getStatus().name(),
                job.getTranscript(), job.getTranslatedTranscript(), job.getLastError()
        );
    }
}
