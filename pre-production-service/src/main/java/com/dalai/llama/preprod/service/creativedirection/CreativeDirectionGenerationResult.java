package com.dalai.llama.preprod.service.creativedirection;

import com.dalai.llama.preprod.service.PreProductionException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** The typed reply of PRE_PROD_CREATIVE_DIRECTION_GENERATE -- the template's OUTPUT block field
 * for field. {@link #validated()} is the gate between the model and the database. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreativeDirectionGenerationResult(
        String recommendationReason,
        List<Direction> directions
) {

    static final int DIRECTION_COUNT = 3;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Direction(
            String title,
            String creativeConcept,
            String directorsTreatment,
            String storytellingStyle,
            VisualLanguage visualLanguage,
            String cinematographyPhilosophy,
            String emotionalJourney,
            String soundDirection,
            String signatureCreativeDevice,
            String creativeRationale,
            List<String> referenceAssetIds
    ) {
        void validate(String where) {
            if (isBlank(title) || isBlank(creativeConcept) || isBlank(directorsTreatment)) {
                throw PreProductionException.upstream(where + " is missing a title, creative concept or director's treatment");
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VisualLanguage(String storyPeriod, String colorTreatment, String contrast, String texture,
                                 String overallAesthetic) {}

    /** Exactly three complete directions and a reason for the recommendation, or an upstream error --
     * a partial or malformed reply is never persisted. */
    public CreativeDirectionGenerationResult validated() {
        if (directions == null || directions.size() != DIRECTION_COUNT) {
            throw PreProductionException.upstream("Creative direction generation returned "
                    + (directions == null ? 0 : directions.size()) + " directions, expected " + DIRECTION_COUNT);
        }
        if (isBlank(recommendationReason)) {
            throw PreProductionException.upstream("Creative direction generation gave no reason for its recommendation");
        }
        for (int i = 0; i < directions.size(); i++) {
            directions.get(i).validate("Creative direction " + (i + 1));
        }
        return this;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
