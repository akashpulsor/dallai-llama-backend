package com.dalai.llama.postprod.service;

import com.dalai.llama.postprod.domain.PostProductionStatus;
import com.dalai.llama.postprod.domain.entity.DubbingJob;
import com.dalai.llama.postprod.repository.DubbingJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/** Same short-transaction-per-state-transition discipline as every other *JobPersistenceService
 * in this module -- see PostProductionJobPersistenceService's class comment for why. */
@Service
public class DubbingJobPersistenceService {

    private final DubbingJobRepository repository;

    public DubbingJobPersistenceService(DubbingJobRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public DubbingJob create(DubbingJob job) {
        job.setStatus(PostProductionStatus.PROCESSING);
        job.setProcessingStartedAt(OffsetDateTime.now());
        return repository.save(job);
    }

    @Transactional
    public DubbingJob recordTranscript(UUID jobId, String sourceLanguage, String transcript) {
        DubbingJob job = requireJob(jobId);
        job.setSourceLanguage(sourceLanguage);
        job.setTranscript(transcript);
        return repository.save(job);
    }

    @Transactional
    public DubbingJob recordTranslation(UUID jobId, String translatedTranscript) {
        DubbingJob job = requireJob(jobId);
        job.setTranslatedTranscript(translatedTranscript);
        return repository.save(job);
    }

    @Transactional
    public DubbingJob finishSuccess(UUID jobId, String outputBucket, String outputObjectKey) {
        DubbingJob job = requireJob(jobId);
        job.setOutputBucket(outputBucket);
        job.setOutputObjectKey(outputObjectKey);
        job.setStatus(PostProductionStatus.COMPLETED);
        job.setCompletedAt(OffsetDateTime.now());
        return repository.save(job);
    }

    @Transactional
    public DubbingJob finishFailure(UUID jobId, String errorMessage) {
        DubbingJob job = requireJob(jobId);
        job.setStatus(PostProductionStatus.FAILED);
        job.setLastError(errorMessage);
        job.setCompletedAt(OffsetDateTime.now());
        return repository.save(job);
    }

    @Transactional
    public Optional<DubbingJob> reclaimIfStillProcessing(UUID jobId, String reason) {
        DubbingJob job = requireJob(jobId);
        if (job.getStatus() != PostProductionStatus.PROCESSING) {
            return Optional.empty();
        }
        job.setStatus(PostProductionStatus.TIMED_OUT);
        job.setLastError(reason);
        job.setCompletedAt(OffsetDateTime.now());
        return Optional.of(repository.save(job));
    }

    private DubbingJob requireJob(UUID jobId) {
        return repository.findById(jobId)
                .orElseThrow(() -> PostProductionException.notFound("Unknown job_id: " + jobId));
    }
}
