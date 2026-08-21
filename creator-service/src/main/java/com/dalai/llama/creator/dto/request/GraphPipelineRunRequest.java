package com.dalai.llama.creator.dto.request;

/**
 * Input for the opt-in "auto-generate everything" graph run - each stage's own request shape is
 * reused as-is (nullable, same defaults as calling that stage directly) so the orchestrator adds
 * nothing to learn beyond the per-stage endpoints a user already knows.
 */
public record GraphPipelineRunRequest(
        GenerateStoryScriptRequest storyScriptRequest,
        GenerateStoryIdeaScriptRequest screenplayRequest,
        GenerateStoryboardRequest storyboardRequest
) {
}
