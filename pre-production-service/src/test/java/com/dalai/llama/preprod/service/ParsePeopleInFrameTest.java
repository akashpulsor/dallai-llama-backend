package com.dalai.llama.preprod.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** Guards the coercion of LLM peopleInFrame text -> Integer column. Pragya's
 * shot-list generation failed on "many" -- this is the safety net. */
class ParsePeopleInFrameTest {

    @Test
    void plainIntegerParses() {
        assertThat(ShotListGenerationService.parsePeopleInFrame("3")).isEqualTo(3);
        assertThat(ShotListGenerationService.parsePeopleInFrame("12")).isEqualTo(12);
    }

    @Test
    void leadingDigitsParsedFromRange() {
        assertThat(ShotListGenerationService.parsePeopleInFrame("3-5")).isEqualTo(3);
        assertThat(ShotListGenerationService.parsePeopleInFrame("10+")).isEqualTo(10);
        assertThat(ShotListGenerationService.parsePeopleInFrame("12 people")).isEqualTo(12);
    }

    @Test
    void qualitativeWordsReturnNull() {
        assertThat(ShotListGenerationService.parsePeopleInFrame("many")).isNull();
        assertThat(ShotListGenerationService.parsePeopleInFrame("crowd")).isNull();
        assertThat(ShotListGenerationService.parsePeopleInFrame("several")).isNull();
        assertThat(ShotListGenerationService.parsePeopleInFrame("few")).isNull();
    }

    @Test
    void nullAndBlankReturnNull() {
        assertThat(ShotListGenerationService.parsePeopleInFrame(null)).isNull();
        assertThat(ShotListGenerationService.parsePeopleInFrame("")).isNull();
        assertThat(ShotListGenerationService.parsePeopleInFrame("   ")).isNull();
    }
}
