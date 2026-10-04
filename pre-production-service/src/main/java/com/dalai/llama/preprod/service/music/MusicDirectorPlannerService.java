package com.dalai.llama.preprod.service.music;

import com.dalai.llama.preprod.domain.entity.MusicPlanRecord;
import com.dalai.llama.preprod.domain.entity.Screenplay;
import com.dalai.llama.preprod.domain.entity.ScreenplayScene;
import com.dalai.llama.preprod.domain.entity.Script;
import com.dalai.llama.preprod.domain.entity.Shot;
import com.dalai.llama.preprod.domain.entity.ShotDialogueBeat;
import com.dalai.llama.preprod.dto.music.MusicPlan;
import com.dalai.llama.preprod.dto.music.MusicSection;
import com.dalai.llama.preprod.repository.MusicPlanRepository;
import com.dalai.llama.preprod.repository.ScreenplayRepository;
import com.dalai.llama.preprod.repository.ScreenplaySceneRepository;
import com.dalai.llama.preprod.repository.ScriptRepository;
import com.dalai.llama.preprod.repository.ShotDialogueBeatRepository;
import com.dalai.llama.preprod.repository.ShotRepository;
import com.dalai.llama.preprod.service.PreProductionException;
import com.dalai.llama.preprod.service.ProjectConfigService;
import com.dalai.llama.preprod.service.generation.JsonExtraction;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayChatRequest;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayChatRequest.LlmGatewayMessage;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayChatResponse;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Plans one score for a whole project, once the shot list is fixed.
 *
 * <p>Runs beside shot planning rather than inside it. The planner is given the script, the
 * screenplay's scenes and the real shot timeline (including where dialogue actually falls) and
 * asked for a single musical identity plus sections bounded by story, not by cuts. Six shots
 * inside one emotional beat should come back as one section.
 *
 * <p>Generation is a separate step and a separate call. Planning is cheap and re-runnable;
 * generation is billable, so nothing here renders audio as a side effect of planning.
 *
 * <p>Provider-independent: this service names a configured model id and nothing else. Which
 * provider that resolves to, and what its duration unit is, is llm-gateway's business.
 */
@Service
public class MusicDirectorPlannerService {

    private static final Logger log = LoggerFactory.getLogger(MusicDirectorPlannerService.class);
    private static final String TASK_KEY = "PRE_PROD_MUSIC_PLAN";

    private final MusicPlanRepository musicPlanRepository;
    private final ScriptRepository scriptRepository;
    private final ScreenplayRepository screenplayRepository;
    private final ScreenplaySceneRepository screenplaySceneRepository;
    private final ShotRepository shotRepository;
    private final ShotDialogueBeatRepository shotDialogueBeatRepository;
    private final LlmGatewayClient llmGatewayClient;
    private final ObjectMapper objectMapper;
    private final ProjectConfigService projectConfigService;
    private final String planningModel;

    public MusicDirectorPlannerService(
            MusicPlanRepository musicPlanRepository,
            ScriptRepository scriptRepository,
            ScreenplayRepository screenplayRepository,
            ScreenplaySceneRepository screenplaySceneRepository,
            ShotRepository shotRepository,
            ShotDialogueBeatRepository shotDialogueBeatRepository,
            LlmGatewayClient llmGatewayClient,
            ObjectMapper objectMapper,
            ProjectConfigService projectConfigService,
            @Value("${pre-production.llm-gateway.default-text-model}") String planningModel
    ) {
        this.musicPlanRepository = musicPlanRepository;
        this.scriptRepository = scriptRepository;
        this.screenplayRepository = screenplayRepository;
        this.screenplaySceneRepository = screenplaySceneRepository;
        this.shotRepository = shotRepository;
        this.shotDialogueBeatRepository = shotDialogueBeatRepository;
        this.llmGatewayClient = llmGatewayClient;
        this.objectMapper = objectMapper;
        this.projectConfigService = projectConfigService;
        this.planningModel = planningModel;
    }

    /**
     * Builds (or rebuilds) the project's score plan.
     *
     * <p>Total duration is the film's target length, or the shot timeline when that is longer --
     * never anything the model chooses. Shots planned shorter than the film are slowed to fill it
     * (a 30s render conformed to 60s), so the timeline is stretched to the target and the score is
     * composed for the film as it will play. The plan is validated as a timeline before being
     * stored, so a plan with a hole in it never reaches a paid generation.
     */
    @Transactional
    public MusicPlan plan(UUID tenantId, UUID projectId) {
        Script script = scriptRepository.findByProjectId(projectId)
                .orElseThrow(() -> PreProductionException.badRequest("Project " + projectId + " has no script yet"));
        Screenplay screenplay = screenplayRepository.findTopByProjectIdOrderByVersionDesc(projectId)
                .orElseThrow(() -> PreProductionException.badRequest("Project " + projectId + " has no screenplay yet"));
        List<Shot> shots = shotRepository.findByProjectIdOrderByShotNumberAsc(projectId);
        if (shots.isEmpty()) {
            throw PreProductionException.badRequest(
                    "Project " + projectId + " has no shots yet -- music is planned against the fixed shot timeline");
        }
        List<ScreenplayScene> scenes = screenplaySceneRepository.findByScreenplayIdOrderBySceneNumberAsc(screenplay.getId());

        Timeline timeline = buildTimeline(shots).stretchedTo(targetSeconds(projectId));
        String context = describeForPlanner(script, scenes, timeline);

        LlmGatewayChatResponse response = llmGatewayClient.chat(
                tenantId.toString(),
                // Duration + shot count in the key: re-planning the same fixed timeline replays,
                // while a changed edit genuinely re-plans. Same reasoning as the shot-list key.
                "music-plan-" + projectId + "-" + timeline.totalSeconds() + "-" + shots.size(),
                new LlmGatewayChatRequest(planningModel, List.of(new LlmGatewayMessage("user", "")),
                        JsonExtraction.JSON_MODE_PARAMS, TASK_KEY,
                        Map.of("timeline", context,
                                "totalDurationSeconds", String.format(Locale.ROOT, "%.2f", timeline.totalSeconds())))
                        .withProjectId(projectId));

        MusicPlan parsed = parse(response);
        List<MusicSection> ordered = MusicPlanValidator.validate(parsed, timeline.totalSeconds());
        String masterPrompt = MasterMusicPromptComposer.compose(parsed, ordered, timeline.totalSeconds());
        MusicPlan plan = new MusicPlan(parsed.globalIdentity(), ordered, masterPrompt,
                parsed.endingStrategy(), timeline.totalSeconds());

        store(tenantId, projectId, plan, timeline.totalSeconds());
        log.info("Planned score projectId={} shots={} sections={} durationSeconds={}",
                projectId, shots.size(), ordered.size(), timeline.totalSeconds());
        return plan;
    }

    /**
     * The stored plan, plus whether the shot timeline has moved under it.
     *
     * <p>A plan is scored against the timeline that existed when it was planned. Retime a shot,
     * delete one, or add one, and the score no longer matches the film -- it would be laid under
     * a runtime it was not composed for. Nothing recomputes automatically (re-planning is a
     * model call, and silently spending on one because a creator nudged a duration would be
     * worse), so the staleness is surfaced and the creator decides.
     */
    @Transactional(readOnly = true)
    public MusicPlanView getView(UUID tenantId, UUID projectId) {
        MusicPlanRecord record = require(tenantId, projectId);
        MusicPlan plan = toPlan(record);
        double planned = record.getTotalDurationSeconds().doubleValue();
        double current = Math.max(currentTimelineSeconds(projectId), targetSeconds(projectId));
        // Same tolerance the validator uses: sub-frame drift is not a changed edit.
        boolean stale = Math.abs(current - planned) > 0.05;
        return new MusicPlanView(plan, stale, current);
    }

    @Transactional(readOnly = true)
    public MusicPlan get(UUID tenantId, UUID projectId) {
        return toPlan(require(tenantId, projectId));
    }

    /** The length the film is meant to be, 0 when none was set. */
    private double targetSeconds(UUID projectId) {
        var config = projectConfigService.getEntityOrDefault(projectId);
        return config == null || config.getTargetDurationSeconds() == null ? 0 : config.getTargetDurationSeconds();
    }

    /** The film's length as the shots currently stand. */
    private double currentTimelineSeconds(UUID projectId) {
        return shotRepository.findByProjectIdOrderByShotNumberAsc(projectId).stream()
                .mapToDouble(shot -> shot.getDurationSeconds() == null ? 0 : shot.getDurationSeconds())
                .sum();
    }

    /**
     * A plan with the one fact the creator needs that is not in the plan itself: whether it still
     * fits the film.
     *
     * @param stale                  true when the shot timeline has changed since planning, so the
     *                               score is composed for a different runtime than the film now has.
     * @param currentTimelineSeconds what the film is now, for the UI to show against the planned length.
     */
    public record MusicPlanView(MusicPlan plan, boolean stale, double currentTimelineSeconds) {}

    /**
     * Hand-edits the prompt the score is generated from, leaving the structured plan intact.
     *
     * <p>Kept separate from re-planning because they answer different questions: re-planning asks
     * the model to reconsider the music, editing asks for exactly this wording. A creator who has
     * tuned a prompt should not lose it to a re-plan they did not ask for.
     */
    @Transactional
    public MusicPlan updateMasterPrompt(UUID tenantId, UUID projectId, String masterPrompt) {
        if (masterPrompt == null || masterPrompt.isBlank()) {
            throw PreProductionException.badRequest("Master music prompt cannot be empty");
        }
        if (masterPrompt.trim().length() > MasterMusicPromptComposer.MAX_PROMPT_CHARS) {
            throw PreProductionException.badRequest("The music prompt is " + masterPrompt.trim().length()
                    + " characters; the music model takes at most " + MasterMusicPromptComposer.MAX_PROMPT_CHARS + ".");
        }
        MusicPlanRecord record = require(tenantId, projectId);
        record.setMasterPrompt(masterPrompt.trim());
        record.setUpdatedAt(OffsetDateTime.now());
        return toPlan(musicPlanRepository.save(record));
    }

    /** Restores the prompt to what the stored plan composes to -- the escape hatch from an edit
     * that went wrong, and the reason the structured plan is the source of truth. */
    @Transactional
    public MusicPlan recomposeMasterPrompt(UUID tenantId, UUID projectId) {
        MusicPlanRecord record = require(tenantId, projectId);
        MusicPlan stored = toPlan(record);
        List<MusicSection> ordered = MusicPlanValidator.validate(stored, stored.totalDurationSeconds());
        record.setMasterPrompt(MasterMusicPromptComposer.compose(stored, ordered, stored.totalDurationSeconds()));
        record.setUpdatedAt(OffsetDateTime.now());
        return toPlan(musicPlanRepository.save(record));
    }

    MusicPlanRecord require(UUID tenantId, UUID projectId) {
        return musicPlanRepository.findByProjectIdAndTenantId(projectId, tenantId)
                .orElseThrow(() -> PreProductionException.notFound("Project " + projectId + " has no music plan yet"));
    }

    MusicPlan toPlan(MusicPlanRecord record) {
        try {
            MusicPlan stored = objectMapper.readValue(record.getPlanJson(), MusicPlan.class);
            return new MusicPlan(stored.globalIdentity(), stored.sections(), record.getMasterPrompt(),
                    record.getEndingStrategy(), record.getTotalDurationSeconds().doubleValue());
        } catch (Exception ex) {
            throw PreProductionException.upstream("Stored music plan could not be read: " + ex.getMessage());
        }
    }

    private void store(UUID tenantId, UUID projectId, MusicPlan plan, double totalSeconds) {
        OffsetDateTime now = OffsetDateTime.now();
        MusicPlanRecord record = musicPlanRepository.findByProjectIdAndTenantId(projectId, tenantId)
                .orElseGet(() -> MusicPlanRecord.builder()
                        .id(UUID.randomUUID()).tenantId(tenantId).projectId(projectId).createdAt(now).build());
        try {
            record.setPlanJson(objectMapper.writeValueAsString(plan));
        } catch (Exception ex) {
            throw PreProductionException.upstream("Could not store the music plan: " + ex.getMessage());
        }
        record.setTotalDurationSeconds(BigDecimal.valueOf(totalSeconds).setScale(2, RoundingMode.HALF_UP));
        record.setMasterPrompt(plan.masterPrompt());
        record.setEndingStrategy(plan.endingStrategy());
        record.setUpdatedAt(now);
        musicPlanRepository.save(record);
    }

    private MusicPlan parse(LlmGatewayChatResponse response) {
        if (response == null || response.response() == null || response.response().isBlank()) {
            throw PreProductionException.upstream("llm-gateway returned no content for " + TASK_KEY);
        }
        try {
            return objectMapper.readValue(JsonExtraction.stripCodeFence(response.response()), MusicPlan.class);
        } catch (Exception ex) {
            throw PreProductionException.upstream("Could not parse " + TASK_KEY + " response as JSON: " + ex.getMessage());
        }
    }

    /** Shot start/end times derived by running durations, because a shot row knows its length but
     * not where it sits in the film. This is the timeline the score has to cover exactly. */
    private Timeline buildTimeline(List<Shot> shots) {
        List<ShotWindow> windows = new ArrayList<>();
        double cursor = 0;
        for (Shot shot : shots) {
            double duration = shot.getDurationSeconds() == null ? 0 : shot.getDurationSeconds();
            boolean spoken = !shotDialogueBeatRepository.findByShotIdOrderByOrderIndexAsc(shot.getId()).isEmpty()
                    || (shot.getVoiceOver() != null && !shot.getVoiceOver().isBlank());
            windows.add(new ShotWindow(shot, cursor, cursor + duration, spoken));
            cursor += duration;
        }
        if (cursor <= 0) {
            throw PreProductionException.badRequest("Every shot has zero duration, so there is no timeline to score");
        }
        return new Timeline(windows, cursor);
    }

    /** What the planner is shown: the story, then the timeline with dialogue marked. Dialogue
     * windows are included so the plan can thin the arrangement underneath speech rather than
     * stopping for it -- ducking levels remain the mixer's job. */
    private String describeForPlanner(Script script, List<ScreenplayScene> scenes, Timeline timeline) {
        StringBuilder out = new StringBuilder();
        out.append("SCRIPT\n").append(script.getScriptText() == null ? "" : script.getScriptText()).append("\n\n");

        out.append("SCENES\n");
        for (ScreenplayScene scene : scenes) {
            out.append("- scene ").append(scene.getSceneNumber())
               .append(" | ").append(scene.getSlug() == null ? "" : scene.getSlug())
               .append(" | ").append(scene.getSummary() == null ? "" : scene.getSummary());
            if (scene.getEmotionalPurpose() != null && !scene.getEmotionalPurpose().isBlank()) {
                out.append(" | emotional purpose: ").append(scene.getEmotionalPurpose());
            }
            out.append('\n');
        }

        out.append("\nSHOT TIMELINE (seconds from start)\n");
        for (ShotWindow window : timeline.windows()) {
            Shot shot = window.shot();
            out.append(String.format(Locale.ROOT, "- %.1f-%.1f | %s", window.start(), window.end(), shot.getShotRef()));
            if (shot.getAction() != null && !shot.getAction().isBlank()) {
                out.append(" | ").append(shot.getAction());
            }
            if (shot.getEmotion() != null && !shot.getEmotion().isBlank()) {
                out.append(" | emotion: ").append(shot.getEmotion());
            }
            out.append(window.spoken() ? " | DIALOGUE" : " | no dialogue").append('\n');
        }
        out.append("\nTOTAL DURATION: ")
           .append(String.format(Locale.ROOT, "%.2f", timeline.totalSeconds())).append(" seconds\n");
        return out.toString();
    }

    record ShotWindow(Shot shot, double start, double end, boolean spoken) {}

    record Timeline(List<ShotWindow> windows, double totalSeconds) {

        /** The same shots spread evenly over a longer film: a shot planned at 0-5s of a 30s
         * timeline sits at 0-10s of a 60s film. A target no longer than the timeline changes nothing. */
        Timeline stretchedTo(double targetSeconds) {
            if (targetSeconds <= totalSeconds) {
                return this;
            }
            double factor = targetSeconds / totalSeconds;
            List<ShotWindow> stretched = windows.stream()
                    .map(w -> new ShotWindow(w.shot(), w.start() * factor, w.end() * factor, w.spoken()))
                    .toList();
            return new Timeline(stretched, targetSeconds);
        }
    }
}
