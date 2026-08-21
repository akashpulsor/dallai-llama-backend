package com.dalai.llama.postprod.service;

import com.dalai.llama.postprod.domain.PostProductionStatus;
import com.dalai.llama.postprod.domain.entity.DialogueSyncJob;
import com.dalai.llama.postprod.repository.DialogueSyncJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Same short-transaction-per-state-transition discipline as PostProductionJobPersistenceService,
 * applied to the dialogue-sync step's own two-provider-call lifecycle. */
@Service
public class DialogueSyncJobPersistenceService {

    private final DialogueSyncJobRepository repository;

    public DialogueSyncJobPersistenceService(DialogueSyncJobRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public DialogueSyncJob create(DialogueSyncJob job) {
        job.setStatus(PostProductionStatus.PROCESSING);
        job.setProcessingStartedAt(OffsetDateTime.now());
        return repository.save(job);
    }

    @Transactional
    public DialogueSyncJob recordVoiceCloneDispatched(UUID id, UUID voiceProfileId, String llmGatewayJobId) {
        DialogueSyncJob job = requireJob(id);
        job.setVoiceProfileId(voiceProfileId);
        job.setLlmGatewayVoiceCloneJobId(llmGatewayJobId);
        return repository.save(job);
    }

    @Transactional
    public DialogueSyncJob finishSuccess(UUID id, String llmGatewayLipSyncJobId, String outputBucket, String outputObjectKey) {
        DialogueSyncJob job = requireJob(id);
        job.setLlmGatewayLipSyncJobId(llmGatewayLipSyncJobId);
        job.setOutputBucket(outputBucket);
        job.setOutputObjectKey(outputObjectKey);
        job.setStatus(PostProductionStatus.COMPLETED);
        job.setCompletedAt(OffsetDateTime.now());
        return repository.save(job);
    }

    @Transactional
    public DialogueSyncJob finishFailure(UUID id, String errorMessage) {
        DialogueSyncJob job = requireJob(id);
        job.setStatus(PostProductionStatus.FAILED);
        job.setLastError(errorMessage);
        job.setCompletedAt(OffsetDateTime.now());
        return repository.save(job);
    }

    private DialogueSyncJob requireJob(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> PostProductionException.notFound("Unknown dialogue_sync_job_id: " + id));
    }
}
