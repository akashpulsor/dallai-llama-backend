package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorGenerationJob;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CreatorScreenplayVideoAsyncServiceTest {

    @Test
    void preparesSceneWorkspaceWithoutDispatchingVideoWorker() {
        ScreenplayVideoService screenplayVideoService = mock(ScreenplayVideoService.class);
        GenerationJobService generationJobService = mock(GenerationJobService.class);
        TaskExecutor taskExecutor = mock(TaskExecutor.class);
        CreatorScreenplayVideoAsyncService service = new CreatorScreenplayVideoAsyncService(
                screenplayVideoService,
                generationJobService,
                taskExecutor
        );

        UUID scriptId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        CreatorGenerationJob queued = CreatorGenerationJob.builder()
                .id(jobId)
                .status("RUNNING")
                .progress(100)
                .outputPayload(new LinkedHashMap<>(Map.of(
                        "runId", runId.toString(),
                        "videoRun", Map.of(
                                "runId", runId.toString(),
                                "status", "SCENES_READY_FOR_GENERATION"
                        )
                )))
                .build();
        CreatorGenerationJob completed = CreatorGenerationJob.builder()
                .id(jobId)
                .status("COMPLETED")
                .progress(100)
                .outputPayload(queued.getOutputPayload())
                .build();
        Map<String, Object> request = Map.of(
                "generationWorkflow", "scene_by_scene",
                "prepareOnly", true
        );

        when(screenplayVideoService.startVideoGenerationJob(scriptId, request, "tenant", "user"))
                .thenReturn(queued);
        when(generationJobService.completeGenerationJob(eq(jobId), anyMap()))
                .thenReturn(completed);

        CreatorGenerationJob result = service.startVideoGeneration(
                scriptId,
                request,
                "tenant",
                "user"
        );

        assertEquals("COMPLETED", result.getStatus());
        verify(generationJobService).completeGenerationJob(eq(jobId), anyMap());
        verify(taskExecutor, never()).execute(org.mockito.ArgumentMatchers.any(Runnable.class));
        verify(screenplayVideoService, never()).runVideoGenerationJob(
                eq(jobId),
                eq(scriptId),
                eq(runId),
                eq(request),
                eq("tenant"),
                eq("user")
        );
    }
}
