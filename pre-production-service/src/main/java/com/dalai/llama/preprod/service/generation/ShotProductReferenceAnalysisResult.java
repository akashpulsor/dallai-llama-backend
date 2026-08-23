package com.dalai.llama.preprod.service.generation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Shape of the JSON llm-gateway's PRE_PROD_PRODUCT_REFERENCE_CAST_DESCRIBE and
 * PRE_PROD_PRODUCT_REFERENCE_INSPIRATION_ANALYZE tasks return -- one record covers both since a
 * given call only ever populates the fields its own prompt asked for. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ShotProductReferenceAnalysisResult(
        String personDescription,
        String detectedSubject,
        String dominantMood,
        String cameraAngle,
        String lightingStyle,
        String motion
) {
}
