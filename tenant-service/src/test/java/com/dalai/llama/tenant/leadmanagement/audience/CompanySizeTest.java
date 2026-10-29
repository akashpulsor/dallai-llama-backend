package com.dalai.llama.tenant.leadmanagement.audience;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CompanySizeTest {

    @Test
    void wordsAndHeadcountsMapToABand() {
        assertThat(CompanySize.parse("Small")).isEqualTo(CompanySize.SMALL);
        assertThat(CompanySize.parse("SME")).isEqualTo(CompanySize.SMALL);
        assertThat(CompanySize.parse("start-up")).isEqualTo(CompanySize.SMALL);
        assertThat(CompanySize.parse("Mid-size")).isEqualTo(CompanySize.MEDIUM);
        assertThat(CompanySize.parse("medium")).isEqualTo(CompanySize.MEDIUM);
        assertThat(CompanySize.parse("Large")).isEqualTo(CompanySize.LARGE);
        assertThat(CompanySize.parse("Enterprise")).isEqualTo(CompanySize.ENTERPRISE);
        assertThat(CompanySize.parse("Corporate / MNC")).isEqualTo(CompanySize.ENTERPRISE);

        assertThat(CompanySize.parse("11-50")).isEqualTo(CompanySize.SMALL);
        assertThat(CompanySize.parse("51-200")).isEqualTo(CompanySize.MEDIUM);
        assertThat(CompanySize.parse("250+")).isEqualTo(CompanySize.LARGE);
        assertThat(CompanySize.parse("1,500 employees")).isEqualTo(CompanySize.ENTERPRISE);
    }

    @Test
    void nothingRecognisableIsUnknown() {
        assertThat(CompanySize.parse(null)).isNull();
        assertThat(CompanySize.parse("  ")).isNull();
        assertThat(CompanySize.parse("unknown")).isNull();
    }
}
