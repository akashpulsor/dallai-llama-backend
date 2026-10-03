package com.dalai.llama.preprod.dto;

import java.util.List;
import java.util.UUID;

/**
 * The Creative Direction screen for one project: the idea and brief the latest alternatives were
 * written from, those alternatives (the AI's recommendation first), and the approved direction --
 * which may come from an earlier round. {@code required} is false for projects created before
 * Creative Direction existed: they may still use it, but nothing downstream waits on it.
 * {@code round} is 0 and {@code directions} empty until the first generation.
 */
public record CreativeDirectionBoardView(
        UUID projectId,
        boolean required,
        int round,
        Idea idea,
        String briefText,
        Integer durationSeconds,
        List<CreativeDirectionView> directions,
        CreativeDirectionView approved
) {

    public record Idea(String title, String concept, String targetAudience, String campaignAngle,
                       String keyMessage, String tone) {}
}
