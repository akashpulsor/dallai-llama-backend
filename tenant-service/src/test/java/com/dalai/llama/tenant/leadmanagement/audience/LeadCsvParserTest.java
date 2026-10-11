package com.dalai.llama.tenant.leadmanagement.audience;

import com.dalai.llama.tenant.leadmanagement.audience.LeadCsvParser.ParsedRow;
import com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LeadCsvParserTest {

    @Test
    void readsNamedColumnsQuotesAndSeveralContactPointsPerRow() {
        List<ParsedRow> rows = LeadCsvParser.parse("""
                First Name,Last Name,Company,E-mail,Email 2,Phone,Industry,Website,Notes\r
                Asha,Rao,"Hearth Foods, Pvt",asha@hearth.example,"a.rao@hearth.example; asha@gmail.example",+91 98765 43210,Food & Beverage,https://hearth.example,"likes ""ghee"" films"\r
                ,,,,,,,,\r
                Bob,,Bolt,bob@bolt.example,,,,not-a-url,\r
                """);

        assertThat(rows).hasSize(2);
        ParsedRow asha = rows.get(0);
        assertThat(asha.rowNumber()).isEqualTo(2);
        assertThat(asha.name()).isEqualTo("Asha Rao");
        assertThat(asha.company()).isEqualTo("Hearth Foods, Pvt");
        assertThat(asha.emails()).containsExactly("asha@hearth.example", "a.rao@hearth.example", "asha@gmail.example");
        assertThat(asha.phones()).containsExactly("+91 98765 43210");
        assertThat(asha.industry()).isEqualTo(ShowcaseIndustry.FOOD_BEVERAGE);
        assertThat(asha.website()).isEqualTo("https://hearth.example");
        assertThat(asha.raw()).startsWith("Asha,Rao,\"Hearth Foods, Pvt\"").contains("\"\"ghee\"\"");
        assertThat(rows.get(1).website()).isNull();
        assertThat(rows.get(1).rowNumber()).isEqualTo(4);
    }

    @Test
    void readsTheDesignationUnderItsCommonNames() {
        for (String header : new String[]{"Designation", "Job Title", "position", "Role"}) {
            ParsedRow row = LeadCsvParser.parse("name," + header + ",company,email\nAsha Rao,Marketing Head,Hearth Foods,asha@hearth.example\n").get(0);
            assertThat(row.designation()).as(header).isEqualTo("Marketing Head");
            assertThat(row.company()).isEqualTo("Hearth Foods");
        }
    }

    @Test
    void needsAHeaderWithAnEmailOrPhoneColumn() {
        assertThatThrownBy(() -> LeadCsvParser.parse("name,company\nAsha,Hearth\n")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LeadCsvParser.parse("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void phonesAndEmailsNormaliseToOneValue() {
        assertThat(ContactPointValues.normalisePhone("+91 (987) 654-3210")).contains("+919876543210");
        assertThat(ContactPointValues.normalisePhone("12345")).isEmpty();
        assertThat(ContactPointValues.normaliseEmail("  Asha@Hearth.EXAMPLE ")).isEqualTo("asha@hearth.example");
        assertThat(ContactPointValues.validEmail("asha@hearth")).isFalse();
    }
}
