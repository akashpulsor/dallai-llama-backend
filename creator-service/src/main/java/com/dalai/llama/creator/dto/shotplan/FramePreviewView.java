package com.dalai.llama.creator.dto.shotplan;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** cameraPlanSheetTag.framePreview - see {@link StoryboardTagView} for the read-view contract. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FramePreviewView(
        @JsonProperty("aspectRatio") String aspectRatio,
        @JsonProperty("headroomPercent") Double headroomPercent,
        @JsonProperty("leadRoomPercent") Double leadRoomPercent,
        @JsonProperty("subjectPlacement") String subjectPlacement,
        @JsonProperty("captionPosition") String captionPosition,
        @JsonProperty("mobileFocusArea") String mobileFocusArea,
        @JsonProperty("lensCompressionFeel") String lensCompressionFeel
) {
    public String subjectPlacement() {
        return subjectPlacement == null ? "" : subjectPlacement;
    }

    public String captionPosition() {
        return captionPosition == null ? "" : captionPosition;
    }
}
