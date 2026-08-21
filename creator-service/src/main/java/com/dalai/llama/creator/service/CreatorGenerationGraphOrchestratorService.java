package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorGenerationJob;
import com.dalai.llama.creator.domain.entity.CreatorScript;
import com.dalai.llama.creator.dto.request.GraphPipelineRunRequest;
import com.dalai.llama.creator.dto.response.GeneratedScriptResponse;
import com.dalai.llama.creator.dto.response.GeneratedStoryScriptResponse;
import com.dalai.llama.creator.dto.response.StoryboardResponse;
import com.dalai.llama.creator.repository.CreatorScriptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Opt-in "auto-generate everything" entry point - chains idea -> story script -> screenplay ->
 * shot plan -> storyboard through their existing, already-critic-and-retry-equipped stage
 * methods, one CreatorGenerationJob per run so the graph's progress/trace is visible the same way
 * every other async job already is in this codebase (see CreatorScreenplayAsyncService, the
 * established pattern this class follows).
 *
 * Each stage method called here is unchanged and already does its own critique/retry internally
 * (HookBeatPlanningService+ScriptCriticService inside IdeaService, ShotPlanCriticService+
 * ContinuityCriticService inside ProductionPlanTagService, ProductFrameCriticService+
 * EditingPlanningService+SoundDesignPlanningService inside StoryboardService) - this class adds
 * nothing to the AI graph itself, it only sequences stages that already existed as separate manual
 * steps and records a readable trace of what happened at each one.
 *
 * Deliberately stops after the storyboard stage, not video: video generation is the expensive,
 * no-retry stage by design (see plan §Budget reality) and the pipeline is built around a human
 * reviewing the storyboard/plan before committing to a video render - auto-triggering video here
 * would remove the human-in-the-loop checkpoint the product is built around.
 */
@Service
public class CreatorGenerationGraphOrchestratorService {

    private static final Logger log = LoggerFactory.getLogger(CreatorGenerationGraphOrchestratorService.class);
    private static final String JOB_TYPE = "GRAPH_PIPELINE_RUN";

    private final IdeaService ideaService;
    private final ProductionPlanTagService productionPlanTagService;
    private final StoryboardService storyboardService;
    private final CreatorScriptRepository scriptRepository;
    private final GenerationJobService generationJobService;
    private final TaskExecutor taskExecutor;

    public CreatorGenerationGraphOrchestratorService(
            IdeaService ideaService,
            ProductionPlanTagService productionPlanTagService,
            StoryboardService storyboardService,
            CreatorScriptRepository scriptRepository,
            GenerationJobService generationJobService,
            @Qualifier("creatorTaskExecutor") TaskExecutor taskExecutor
    ) {
        this.ideaService = ideaService;
        this.productionPlanTagService = productionPlanTagService;
        this.storyboardService = storyboardService;
        this.scriptRepository = scriptRepository;
        this.generationJobService = generationJobService;
        this.taskExecutor = taskExecutor;
    }

    public CreatorGenerationJob startPipeline(
            UUID lockedIdeaId,
            UUID storyIdeaId,
            GraphPipelineRunRequest request,
            String tenantId,
            String userId
    ) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("lockedIdeaId", lockedIdeaId == null ? null : lockedIdeaId.toString());
        input.put("storyIdeaId", storyIdeaId == null ? null : storyIdeaId.toString());
        CreatorGenerationJob job = generationJobService.startGenerationJob(JOB_TYPE, tenantId, userId, null, input);
        taskExecutor.execute(() -> runPipeline(job.getId(), lockedIdeaId, storyIdeaId, request, tenantId, userId));
        return job;
    }

    private void runPipeline(
            UUID jobId,
            UUID lockedIdeaId,
            UUID storyIdeaId,
            GraphPipelineRunRequest request,
            String tenantId,
            String userId
    ) {
        if (!generationJobService.claimGenerationJobExecution(jobId, "graph-pipeline-run")) {
            log.info("Skipping duplicate graph pipeline execution jobId={} lockedIdeaId={} storyIdeaId={}", jobId, lockedIdeaId, storyIdeaId);
            return;
        }
        List<Map<String, Object>> trace = new ArrayList<>();
        Map<String, Object> jobOutput = new LinkedHashMap<>();
        try {
            generationJobService.updateGenerationJobProgress(jobId, 5, "Planning story structure (hook + beats), then writing the story script", Map.of("trace", trace));
            GeneratedStoryScriptResponse storyScriptResponse = ideaService.generateStoryScriptForStoryIdea(
                    lockedIdeaId, storyIdeaId, request == null ? null : request.storyScriptRequest(), tenantId, userId
            );
            addTrace(trace, "STORY_SCRIPT", "COMPLETED", "Story script generated and critiqued against the approved beat plan.");
            jobOutput.put("storyIdeaId", storyIdeaId == null ? null : storyIdeaId.toString());

            generationJobService.updateGenerationJobProgress(jobId, 25, "Generating shot-wise screenplay", Map.of("trace", trace));
            GeneratedScriptResponse screenplayResponse = ideaService.generateScriptForStoryIdea(
                    lockedIdeaId, storyIdeaId, request == null ? null : request.screenplayRequest(), tenantId, userId
            );
            UUID scriptId = screenplayResponse.getScriptId();
            addTrace(trace, "SCREENPLAY", "COMPLETED", "Shot-wise screenplay generated.");
            jobOutput.put("scriptId", scriptId == null ? null : scriptId.toString());

            generationJobService.updateGenerationJobProgress(jobId, 50, "Planning shots - director/DP/gaffer pass with quality critique", Map.of("trace", trace));
            CreatorScript script = scriptRepository.findById(scriptId)
                    .orElseThrow(() -> new IllegalStateException("Generated script was not found: " + scriptId));
            var shotPlans = productionPlanTagService.generateTagsForScript(
                    script, script.getScriptPayload(), script.getShots(), ProductionPlanTagService.DEFAULT_STYLE_KEY
            );
            addTrace(trace, "SHOT_PLAN", "COMPLETED", shotPlans.size() + " shot(s) planned and critiqued (lighting, camera, continuity, shot-type variety).");
            jobOutput.put("shotPlanCount", shotPlans.size());

            generationJobService.updateGenerationJobProgress(jobId, 75, "Generating storyboard frames, editing plan, and sound design plan", Map.of("trace", trace));
            StoryboardResponse storyboardResponse = storyboardService.generateFromFinalScript(
                    scriptId, request == null ? null : request.storyboardRequest(), tenantId, userId
            );
            addTrace(trace, "STORYBOARD", "COMPLETED", "Storyboard frames generated and critiqued against the Pinterest-reference bar; editing and sound design plans generated.");
            jobOutput.put("storyboardId", storyboardResponse.storyboardId() == null ? null : storyboardResponse.storyboardId().toString());

            jobOutput.put("trace", trace);
            jobOutput.put("message", "Pipeline complete through storyboard. Review the storyboard and plans, then trigger video generation manually.");
            generationJobService.completeGenerationJob(jobId, jobOutput);
        } catch (RuntimeException ex) {
            addTrace(trace, "PIPELINE", "FAILED", defaultString(ex.getMessage(), ex.getClass().getSimpleName()));
            jobOutput.put("trace", trace);
            generationJobService.failGenerationJob(jobId, defaultString(ex.getMessage(), ex.getClass().getSimpleName()), jobOutput);
            log.error(
                    "Graph pipeline run failed jobId={} lockedIdeaId={} storyIdeaId={} tenantId={} userId={} errorType={} errorMessage={}",
                    jobId, lockedIdeaId, storyIdeaId, tenantId, userId, ex.getClass().getSimpleName(), ex.getMessage(), ex
            );
        }
    }

    private void addTrace(List<Map<String, Object>> trace, String stage, String status, String summary) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("stage", stage);
        row.put("status", status);
        row.put("summary", summary);
        row.put("timestamp", OffsetDateTime.now().toString());
        trace.add(row);
    }

    private String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
