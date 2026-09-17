package com.dalai.llama.videogen.service;

import com.dalai.llama.videogen.domain.JobStatus;
import com.dalai.llama.videogen.domain.entity.DubJob;
import com.dalai.llama.videogen.kafka.DubRequestedEvent;
import com.dalai.llama.videogen.kafka.DubRequestedPublisher;
import com.dalai.llama.videogen.repository.DubJobRepository;
import com.dalai.llama.videogen.web.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Queueing a dub and reporting on it.
 *
 * <p>Dubbing used to be an in-flight HTTP call and nothing else, so there was nothing for a page to
 * ask about while it ran -- which is why pressing the button twice was a reasonable thing for a
 * creator to do. Now the request records a job and returns, and the page polls that job.
 *
 * <p>Split from {@link CloneVoiceService} deliberately: that class knows how to make a voice say
 * something, this one knows how a request for one is tracked. Putting the job handling inside it
 * would mean every caller of the synthesis path -- the project-wide clone, the fit flow -- also
 * carried job semantics it does not want.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DubJobService {

    private final DubJobRepository dubJobRepository;
    private final DubRequestedPublisher dubRequestedPublisher;
    private final CloneVoiceService cloneVoiceService;

    /** Records the request and returns immediately. */
    @Transactional
    public DubJob request(TenantContext context, UUID projectId, UUID shotId, String text) {
        DubJob job = dubJobRepository.save(DubJob.builder()
                .jobId(UUID.randomUUID())
                .tenantId(context.tenantId())
                .projectId(projectId)
                .shotId(shotId)
                .text(text)
                .status(JobStatus.QUEUED)
                .createdBy(context.userId())
                .createdAt(OffsetDateTime.now())
                .build());
        try {
            dubRequestedPublisher.publish(new DubRequestedEvent(
                    job.getJobId(), context.tenantId().toString(), projectId, shotId, text, context.userId()));
        } catch (RuntimeException ex) {
            // A QUEUED row with no event behind it would never finish and never fail, so the
            // failure is recorded where the page is already looking.
            job.setStatus(JobStatus.FAILED);
            job.setLastError("Could not queue the dub: " + ex.getMessage());
            job.setCompletedAt(OffsetDateTime.now());
            dubJobRepository.save(job);
            throw ex;
        }
        log.info("Queued a dub jobId={} shotId={}", job.getJobId(), shotId);
        return job;
    }

    /** Runs the dub. Called from the consumer, never from a request thread. */
    public void run(TenantContext context, UUID jobId, UUID projectId, UUID shotId, String text) {
        markProcessing(jobId);
        try {
            CloneVoiceService.CloneVoiceResult result =
                    cloneVoiceService.cloneVoice(context.tenantId(), projectId, shotId, text);
            finishSuccess(jobId, result.audioUrl());
            log.info("Dub finished jobId={} shotId={} mode={}", jobId, shotId, result.mode());
        } catch (RuntimeException ex) {
            log.warn("Dub failed jobId={} shotId={}: {}", jobId, shotId, ex.getMessage());
            finishFailure(jobId, ex.getMessage());
            throw ex;
        }
    }

    @Transactional(readOnly = true)
    public Optional<DubJob> get(UUID tenantId, UUID jobId) {
        return dubJobRepository.findById(jobId).filter(job -> tenantId.equals(job.getTenantId()));
    }

    /** The newest dub for this shot, which is what a page reopening a card wants to know about. */
    @Transactional(readOnly = true)
    public Optional<DubJob> latestForShot(UUID tenantId, UUID shotId) {
        return dubJobRepository.findTopByShotIdOrderByCreatedAtDesc(shotId)
                .filter(job -> tenantId.equals(job.getTenantId()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void markProcessing(UUID jobId) {
        dubJobRepository.findById(jobId).ifPresent(job -> {
            job.setStatus(JobStatus.PROCESSING);
            dubJobRepository.save(job);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void finishSuccess(UUID jobId, String audioUrl) {
        dubJobRepository.findById(jobId).ifPresent(job -> {
            job.setStatus(JobStatus.COMPLETED);
            job.setAudioUrl(audioUrl);
            job.setCompletedAt(OffsetDateTime.now());
            dubJobRepository.save(job);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void finishFailure(UUID jobId, String reason) {
        dubJobRepository.findById(jobId).ifPresent(job -> {
            job.setStatus(JobStatus.FAILED);
            job.setLastError(reason);
            job.setCompletedAt(OffsetDateTime.now());
            dubJobRepository.save(job);
        });
    }
}
