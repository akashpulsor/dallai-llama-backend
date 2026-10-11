package com.dalai.llama.videogen.service.generationplan;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GenerationPlanLlmTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private List<GenerationPlanLlm.IntervalAnswer> read(String json) throws Exception {
        return mapper.readValue(json, GenerationPlanLlm.TimelineAnswer.class).intervals().stream()
                .flatMap(i -> GenerationPlanLlm.normalise(i).stream()).toList();
    }

    @Test
    void everyActionHappeningInAnIntervalIsKeptTogether() throws Exception {
        List<GenerationPlanLlm.IntervalAnswer> intervals = read("""
                {"intervals":[
                  {"startSeconds":0,"endSeconds":0.6,"actionIds":["OPEN","C1"],"action":"Lines draw on"},
                  {"startSeconds":0.6,"endSeconds":2,"actionIds":["A1","D1","C1","END"],"action":"Forms coalesce while the narrator asks"}
                ]}""");

        assertThat(intervals).extracting(GenerationPlanLlm.IntervalAnswer::actionId).containsExactly("OPEN,C1", "A1,D1,C1,END");
        assertThat(intervals.get(0).endSeconds()).isEqualByComparingTo("0.6");
    }

    @Test
    void theOlderSingleIdAndListShapesAreStillRead() throws Exception {
        List<GenerationPlanLlm.IntervalAnswer> intervals = read("""
                {"intervals":[
                  {"startSeconds":0,"endSeconds":1,"actionId":"OPEN","holdRequired":true},
                  {"startSeconds":1,"endSeconds":2,"actionId":["A1","null","A1","END"]}
                ]}""");

        assertThat(intervals).extracting(GenerationPlanLlm.IntervalAnswer::actionId).containsExactly("OPEN", "A1,END");
        assertThat(intervals.get(0).holdRequired()).isTrue();
    }
}
