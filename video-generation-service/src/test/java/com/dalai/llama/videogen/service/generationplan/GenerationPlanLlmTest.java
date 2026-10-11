package com.dalai.llama.videogen.service.generationplan;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GenerationPlanLlmTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void anIntervalThatTimesSeveralActionsIsSplitEvenlyInOrder() throws Exception {
        GenerationPlanLlm.TimelineAnswer answer = mapper.readValue("""
                {"intervals":[
                  {"startSeconds":0,"endSeconds":2,"actionId":"OPEN","action":"Hero stands"},
                  {"startSeconds":2,"endSeconds":8,"actionId":["a1","a2","END"],"action":"Turns, speaks, smiles"}
                ]}""", GenerationPlanLlm.TimelineAnswer.class);

        List<GenerationPlanLlm.IntervalAnswer> intervals = answer.intervals().stream()
                .flatMap(i -> GenerationPlanLlm.normalise(i).stream()).toList();

        assertThat(intervals).extracting(GenerationPlanLlm.IntervalAnswer::actionId).containsExactly("OPEN", "a1", "a2", "END");
        assertThat(intervals.get(1).startSeconds()).isEqualByComparingTo("2");
        assertThat(intervals.get(1).endSeconds()).isEqualByComparingTo("4");
        assertThat(intervals.get(3).startSeconds()).isEqualByComparingTo("6");
        assertThat(intervals.get(3).endSeconds()).isEqualByComparingTo(new BigDecimal("8"));
    }

    @Test
    void aSingleIdPassesThroughUnchanged() throws Exception {
        GenerationPlanLlm.RawInterval raw = mapper.readValue(
                "{\"startSeconds\":0,\"endSeconds\":3,\"actionId\":\"OPEN\",\"holdRequired\":true}", GenerationPlanLlm.RawInterval.class);
        assertThat(GenerationPlanLlm.normalise(raw)).singleElement()
                .satisfies(i -> {
                    assertThat(i.actionId()).isEqualTo("OPEN");
                    assertThat(i.holdRequired()).isTrue();
                });
    }
}
