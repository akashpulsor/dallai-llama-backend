package com.dalai.llama.creator.dto.request;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GenerateStoryIdeaScriptRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void acceptsSavedCampaignObjectiveAtRootAndInsideLockedPackage() {
        GenerateStoryIdeaScriptRequest.BrandContext brandContext = brandContextWithObjective("x".repeat(227));
        GenerateStoryIdeaScriptRequest.ScreenplayProductionPackage lockedPackage =
                new GenerateStoryIdeaScriptRequest.ScreenplayProductionPackage(
                        null,
                        null,
                        null,
                        null,
                        brandContext,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                );
        GenerateStoryIdeaScriptRequest request = new GenerateStoryIdeaScriptRequest(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                brandContext,
                null,
                new GenerateStoryIdeaScriptRequest.WorkflowContext(null, null, lockedPackage, null, null, null)
        );

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void stillRejectsUnboundedCampaignObjectives() {
        GenerateStoryIdeaScriptRequest.BrandContext brandContext = brandContextWithObjective("x".repeat(241));

        assertThat(validator.validate(brandContext))
                .anySatisfy(violation -> {
                    assertThat(violation.getPropertyPath().toString()).isEqualTo("campaignObjective");
                    assertThat(violation.getMessage()).contains("240");
                });
    }

    private GenerateStoryIdeaScriptRequest.BrandContext brandContextWithObjective(String campaignObjective) {
        return new GenerateStoryIdeaScriptRequest.BrandContext(
                null,
                null,
                null,
                null,
                null,
                campaignObjective,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }
}
