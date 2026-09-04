package com.dalai.llama.videogen.service;

import com.dalai.llama.videogen.domain.JobStatus;
import com.dalai.llama.videogen.domain.entity.FinalRenderJob;
import com.dalai.llama.videogen.repository.FinalRenderJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Mirror of {@link VideoGenJobPersistenceService} for the aggregate-render entity -- each
 * method opens its OWN short transaction so the outer {@link FinalRenderService#assemble}
 * flow (which blocks on ffmpeg subprocess for minutes) never holds a DB connection across
 * that whole window. Same reasoning documented on {@code VideoGenJobPersistenceService}.
 */
@Service
@RequiredArgsConstructor
public class FinalRenderJobPersistenceService {

    private final FinalRenderJobRepository finalRenderJobRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FinalRenderJob create(FinalRenderJob job) {
        return finalRenderJobRepository.save(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FinalRenderJob markProcessing(UUID renderId) {
        FinalRenderJob job = requireJob(renderId);
        job.setStatus(JobStatus.PROCESSING);
        job.setProcessingStartedAt(OffsetDateTime.now());
        return finalRenderJobRepository.save(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FinalRenderJob finishSuccess(UUID renderId, String outputBucket, String outputObjectKey, int shotCount, String sourceJobIds) {
        FinalRenderJob job = requireJob(renderId);
        job.setStatus(JobStatus.COMPLETED);
        job.setOutputBucket(outputBucket);
        job.setOutputObjectKey(outputObjectKey);
        job.setShotCount(shotCount);
        job.setSourceJobIds(sourceJobIds);
        job.setCompletedAt(OffsetDateTime.now());
        return finalRenderJobRepository.save(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FinalRenderJob finishFailure(UUID renderId, String errorMessage) {
        FinalRenderJob job = requireJob(renderId);
        job.setStatus(JobStatus.FAILED);
        job.setLastError(errorMessage);
        job.setCompletedAt(OffsetDateTime.now());
        return finalRenderJobRepository.save(job);
    }

    private FinalRenderJob requireJob(UUID renderId) {
        return finalRenderJobRepository.findById(renderId)
                .orElseThrow(() -> VideoGenException.notFound("Unknown render_id=" + renderId));
    }
}
