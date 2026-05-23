package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorGenerationJob;
import com.dalai.llama.creator.dto.request.GenerateStoryboardRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class CreatorStoryboardAsyncService {

    private static final Logger log = LoggerFactory.getLogger(CreatorStoryboardAsyncService.class);

    private final StoryboardService storyboardService;
    private final TaskExecutor taskExecutor;

    public CreatorStoryboardAsyncService(
            StoryboardService storyboardService,
            @Qualifier("creatorTaskExecutor") TaskExecutor taskExecutor
    ) {
        this.storyboardService = storyboardService;
        this.taskExecutor = taskExecutor;
    }

    public CreatorGenerationJob startStoryboardGeneration(
            UUID scriptId,
            GenerateStoryboardRequest request,
            String tenantId,
            String userId
    ) {
        CreatorGenerationJob job = storyboardService.startGenerateFromFinalScriptJob(scriptId, request, tenantId, userId);
        taskExecutor.execute(() -> {
            try {
                storyboardService.runGenerateFromFinalScriptJob(job.getId(), scriptId, request, tenantId, userId);
            } catch (RuntimeException ex) {
                log.error(
                        "Creator async storyboard worker crashed jobId={} scriptId={} tenantId={} userId={} errorType={} errorMessage={}",
                        job.getId(),
                        scriptId,
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
