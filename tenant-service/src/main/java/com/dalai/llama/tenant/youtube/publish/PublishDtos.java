package com.dalai.llama.tenant.youtube.publish;

import com.dalai.llama.tenant.youtube.publish.YouTubePublishStore.Privacy;
import com.dalai.llama.tenant.youtube.publish.YouTubePublishStore.Status;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Requests and views for publishing to YouTube (rules 34–36). */
public final class PublishDtos {

    private PublishDtos() {
    }

    /** YouTube rejects {@code <} and {@code >} in titles and descriptions. */
    static final String NO_ANGLE_BRACKETS = "^[^<>]*$";

    public record PublishRequest(
            @NotNull UUID projectId,
            @NotBlank @Size(max = 100) @Pattern(regexp = NO_ANGLE_BRACKETS, message = "can't contain < or >") String title,
            @Size(max = 5000) @Pattern(regexp = NO_ANGLE_BRACKETS, message = "can't contain < or >") String description,
            @Size(max = 30) List<@Size(max = 60) String> tags,
            @Pattern(regexp = "^\\d{1,3}$") String categoryId,
            @NotNull Privacy privacy,
            /* Optional schedule; the video goes public then (so it needs confirmPublic). */
            Instant publishAt,
            /* Required for PUBLIC and for any schedule: the creator confirmed it will be public. */
            boolean confirmPublic,
            @NotBlank @Size(max = 100) String idempotencyKey
    ) {
    }

    public record EditRequest(
            @NotBlank @Size(max = 100) @Pattern(regexp = NO_ANGLE_BRACKETS, message = "can't contain < or >") String title,
            @Size(max = 5000) @Pattern(regexp = NO_ANGLE_BRACKETS, message = "can't contain < or >") String description,
            @Size(max = 30) List<@Size(max = 60) String> tags,
            @NotNull Privacy privacy,
            Instant publishAt,
            boolean confirmPublic
    ) {
    }

    public record JobView(UUID id, UUID projectId, String title, String description, List<String> tags, Privacy privacy,
                          Instant publishAt, Status status, Long bytesTotal, long bytesSent, int progressPercent, String youtubeVideoId,
                          String youtubeUrl, String youtubePrivacy, boolean hasThumbnail, int attempts, String lastError, String via,
                          Instant createdAt, Instant updatedAt) {
    }

    /** A finished Dalaillama film the creator can publish. */
    public record PublishableFilm(UUID projectId, String name, OffsetDateTime renderedAt, BigDecimal durationSeconds, Integer width,
                                  Integer height, boolean clientConsented) {
    }
}
