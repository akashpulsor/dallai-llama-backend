package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorGenerationJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class CreatorIdeaGenerationAsyncService {

    private static final Logger log = LoggerFactory.getLogger(CreatorIdeaGenerationAsyncService.class);

    private final IdeaService ideaService;
    private final TaskExecutor taskExecutor;

    public CreatorIdeaGenerationAsyncService(
            IdeaService ideaService,
            @Qualifier("creatorTaskExecutor") TaskExecutor taskExecutor
    ) {
        this.ideaService = ideaService;
        this.taskExecutor = taskExecutor;
    }

    public CreatorGenerationJob startIdeaGeneration(
            UUID lockedIdeaId,
            String tenantId,
            String userId,
            Pageable pageable
    ) {
        CreatorGenerationJob job = ideaService.startGenerateIdeasForLockedBriefJob(lockedIdeaId, tenantId, userId, pageable);
        taskExecutor.execute(() -> {
            try {
                ideaService.runGenerateIdeasForLockedBriefJob(job.getId(), lockedIdeaId, tenantId, userId, pageable);
            } catch (RuntimeException ex) {
                log.error(
                        "Creator async idea generation worker crashed jobId={} lockedIdeaId={} tenantId={} userId={} errorType={} errorMessage={}",
                        job.getId(),
                        lockedIdeaId,
                        tenantId,
                        userId,
                        ex.getClass().getSimpleName(),
                        ex.getMessage(),
                        ex
                );
            }
        });
        return job;
    }
}
