package com.dalai.llama.preprod.service.lifecycle;

import com.dalai.llama.preprod.domain.ProjectStatus;
import com.dalai.llama.preprod.service.PreProductionException;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.dalai.llama.preprod.domain.ProjectStatus.CLIENT_LOCKED;
import static com.dalai.llama.preprod.domain.ProjectStatus.DRAFT;
import static com.dalai.llama.preprod.domain.ProjectStatus.IN_PRODUCTION;
import static com.dalai.llama.preprod.domain.ProjectStatus.SCREENPLAY_READY;
import static com.dalai.llama.preprod.domain.ProjectStatus.SCRIPT_READY;
import static com.dalai.llama.preprod.domain.ProjectStatus.SHOT_LIST_READY;
import static com.dalai.llama.preprod.domain.ProjectStatus.VIDEO_GENERATION_COMPLETE;

/**
 * Single source of truth for which {@link ProjectStatus} transitions are legal -- before this,
 * every generation service (script/screenplay/shot-list) mutated {@code Project.status} inline,
 * so nothing actually stopped e.g. a shot-list generation from stamping a project SHOT_LIST_READY
 * before it had a screenplay. Callers now go through {@link
 * com.dalai.llama.preprod.service.ProjectService#advanceStatus}, which asks this table before
 * writing anything.
 * <p>
 * Regenerating an earlier stage (e.g. re-running script generation on a project that already has
 * a shot list) is allowed and intentionally moves status backward -- the later stages are now
 * stale, not deleted (full staleness propagation is deferred past this v1 slice, see the design
 * doc's §9.7).
 */
@Component
public class ProjectStateMachine {

    private static final Map<ProjectStatus, Set<ProjectStatus>> ALLOWED_TRANSITIONS = buildTransitions();

    public ProjectStatus transition(ProjectStatus current, ProjectStatus target) {
        if (current == target) {
            return target;
        }
        Set<ProjectStatus> allowed = ALLOWED_TRANSITIONS.getOrDefault(current, Set.of());
        if (!allowed.contains(target)) {
            throw PreProductionException.conflict("Cannot move project from " + current + " to " + target);
        }
        return target;
    }

    private static Map<ProjectStatus, Set<ProjectStatus>> buildTransitions() {
        Map<ProjectStatus, Set<ProjectStatus>> transitions = new EnumMap<>(ProjectStatus.class);
        // From DRAFT only the first stage (script) can complete.
        transitions.put(DRAFT, EnumSet.of(SCRIPT_READY));
        // From any stage at or past SCRIPT_READY, a caller may move forward one stage, jump back
        // to re-run script generation (the earliest regenerable stage), or all the way back to
        // DRAFT -- selecting a different ProjectIdea on this project (see ProjectIdeaService):
        // every later stage's data is now stale (built from a different idea), not deleted, same
        // "regenerating moves status backward, doesn't destroy anything" philosophy as every other
        // stage here, just one level earlier than SCRIPT_READY.
        transitions.put(SCRIPT_READY, EnumSet.of(SCREENPLAY_READY, DRAFT));
        transitions.put(SCREENPLAY_READY, EnumSet.of(SCRIPT_READY, SHOT_LIST_READY, DRAFT));
        transitions.put(SHOT_LIST_READY, EnumSet.of(SCRIPT_READY, SCREENPLAY_READY, IN_PRODUCTION, CLIENT_LOCKED, DRAFT));
        transitions.put(IN_PRODUCTION, EnumSet.of(SCRIPT_READY, SCREENPLAY_READY, SHOT_LIST_READY, VIDEO_GENERATION_COMPLETE, CLIENT_LOCKED, DRAFT));
        // Every shot's video finished -- same symmetric "regenerate moves backward, forward goes
        // to CLIENT_LOCKED" shape IN_PRODUCTION's own row already has, one stage later. Backward
        // to IN_PRODUCTION too, in case more shots get added/regenerated after this point.
        transitions.put(VIDEO_GENERATION_COMPLETE, EnumSet.of(IN_PRODUCTION, SCRIPT_READY, SCREENPLAY_READY, SHOT_LIST_READY, CLIENT_LOCKED, DRAFT));
        // Regenerating any stage after the client has locked the package moves status backward,
        // same as every other stage -- the package is now stale, not un-locked (re-locking is a
        // fresh POST /v1/public/projects/{token}/lock, not an automatic state change).
        transitions.put(CLIENT_LOCKED, EnumSet.of(SCRIPT_READY, SCREENPLAY_READY, SHOT_LIST_READY, IN_PRODUCTION, VIDEO_GENERATION_COMPLETE, DRAFT));
        return transitions;
    }
}
