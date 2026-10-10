package com.dalai.llama.tenant.showcase.dto;

import com.dalai.llama.tenant.showcase.domain.OfficialUploadStatus;
import com.dalai.llama.tenant.showcase.domain.ShowcaseFormat;
import com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Requests and views for publishing a film made on Dalaillama (CREATOR_SHOWCASE.md §9.3). */
public final class PlatformFilmDtos {

    private PlatformFilmDtos() {
    }

    /** Everything the creator needs to upload the film to their own channel themselves. */
    public record YouTubeKitView(
            String projectName,
            boolean filmReady,
            /* Short-lived link to download the film; null until the film is published. */
            String downloadUrl,
            String title,
            /* First line is our marker; keep it, it is how we recognise the upload. */
            String description,
            List<String> tags,
            String disclosureStep,
            boolean verifiedFunded,
            boolean marketingConsent,
            boolean officialUploadAvailable,
            /* Why "Publish on Dalaillama's channel" is unavailable, or null. */
            String officialUploadBlockedReason
    ) {
    }

    /** The creator pasted the link of the film they uploaded to their own channel. */
    public record LinkPlatformFilmRequest(
            @NotBlank @Size(max = 300) String url,
            @NotNull ShowcaseIndustry industry,
            @NotNull ShowcaseFormat format,
            @Size(max = 80) String clientLabel,
            @AssertTrue(message = "confirm the client agreed to this film being shown") boolean rightsConfirmed
    ) {
    }

    public record OfficialUploadRequest(
            @NotNull ShowcaseIndustry industry,
            @NotNull ShowcaseFormat format,
            @Size(max = 80) String clientLabel,
            @AssertTrue(message = "confirm the client agreed to this film being shown") boolean rightsConfirmed
    ) {
    }

    public record OfficialUploadView(
            OfficialUploadStatus status,
            String youtubeVideoId,
            String error,
            int attempts
    ) {
    }
}
