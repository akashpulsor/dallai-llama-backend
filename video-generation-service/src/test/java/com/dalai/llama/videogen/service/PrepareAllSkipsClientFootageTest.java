package com.dalai.llama.videogen.service;

import com.dalai.llama.videogen.service.preproduction.PreProductionViews;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** "Prepare all" walks every shot in shooting order except the ones the client films. The bundle is
 * read from JSON exactly as pre-production sends it, so the wire field is covered too. */
class PrepareAllSkipsClientFootageTest {

    private final ObjectMapper json = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .findAndRegisterModules();

    @Test
    void clientFootageShotsAreLeftOutOfAWholeProjectRun() throws Exception {
        UUID one = UUID.randomUUID(), testimonial = UUID.randomUUID(), three = UUID.randomUUID(), legacy = UUID.randomUUID();
        PreProductionViews.PrepareBundleView bundle = json.readValue("""
                {"shots": [
                  {"shot": {"id": "%s", "shotNumber": 3, "clientFootage": false}},
                  {"shot": {"id": "%s", "shotNumber": 2, "clientFootage": true}},
                  {"shot": {"id": "%s", "shotNumber": 1}},
                  {"shot": {"id": "%s", "shotNumber": 4, "clientFootage": null}}
                ]}
                """.formatted(three, testimonial, one, legacy), PreProductionViews.PrepareBundleView.class);

        assertThat(PrepareOrchestrationService.allShotIdsInShootingOrder(bundle)).containsExactly(one, three, legacy);
    }
}
