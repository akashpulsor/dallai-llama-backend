package com.dalai.llama.preprod.dto;

import com.dalai.llama.joblifecycle.JobLifecycleStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The Creative Direction screen for one project: the idea and brief the latest completed round was
 * written from, one page of its alternatives (the AI's recommendation first, on page 0), and the
 * approved direction -- which may come from an earlier round. {@code required} is false for
 * projects created before Creative Direction existed: they may still use it, but nothing downstream
 * waits on it. {@code round} is 0 and {@code directions} empty until a round completes.
 * <p>
 * {@code generation} is the newest round's job, which may still be running (or have failed) while
 * the page shows the previous completed round's treatments; null before the first generation.
 */
public record CreativeDirectionBoardView(
        UUID projectId,
        boolean required,
        int round,
        Idea idea,
        String briefText,
        Integer durationSeconds,
        List<CreativeDirectionView> directions,
        int page,
        int size,
        int totalDirections,
        CreativeDirectionView approved,
        Generation generation
) {

    public record Generation(UUID id, int round, JobLifecycleStatus status, int requestedCount, String errorMessage,
                             OffsetDateTime createdAt, OffsetDateTime completedAt) {}


    public record Idea(String title, String concept, String targetAudience, String campaignAngle,
                       String keyMessage, String tone) {}
}
