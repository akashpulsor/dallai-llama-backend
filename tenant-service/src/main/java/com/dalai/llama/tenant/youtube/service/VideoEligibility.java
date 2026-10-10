package com.dalai.llama.tenant.youtube.service;

import com.dalai.llama.tenant.youtube.config.YouTubeProperties;
import com.dalai.llama.tenant.youtube.domain.entity.YouTubeVideo;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Whether a cached video may be showcased (CREATOR_SHOWCASE.md decision 5). Decided on read from
 * the raw facts we cache, so a refresh that changes a fact changes the answer with no extra
 * bookkeeping. */
@Component
public class VideoEligibility {

    public enum Reason { UNAVAILABLE, NOT_PUBLIC, EMBEDDING_OFF, AGE_RESTRICTED, MADE_FOR_KIDS, TOO_SHORT, TOO_LONG }

    private final YouTubeProperties.Eligibility rules;

    public VideoEligibility(YouTubeProperties properties) {
        this.rules = properties.eligibility();
    }

    /** Empty when the video can be showcased; otherwise the first rule it breaks. */
    public Optional<Reason> problem(YouTubeVideo video) {
        if (video.getGoneAt() != null) return Optional.of(Reason.UNAVAILABLE);
        if (!"public".equals(video.getPrivacyStatus())) return Optional.of(Reason.NOT_PUBLIC);
        if (!Boolean.TRUE.equals(video.getEmbeddable())) return Optional.of(Reason.EMBEDDING_OFF);
        if (Boolean.TRUE.equals(video.getAgeRestricted())) return Optional.of(Reason.AGE_RESTRICTED);
        if (Boolean.TRUE.equals(video.getMadeForKids())) return Optional.of(Reason.MADE_FOR_KIDS);
        if (video.getDurationSeconds() == null || video.getDurationSeconds().intValue() < rules.minDurationSeconds()) {
            return Optional.of(Reason.TOO_SHORT);
        }
        if (video.getDurationSeconds().intValue() > rules.maxDurationSeconds()) return Optional.of(Reason.TOO_LONG);
        return Optional.empty();
    }
}
