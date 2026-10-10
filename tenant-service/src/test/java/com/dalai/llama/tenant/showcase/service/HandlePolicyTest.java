package com.dalai.llama.tenant.showcase.service;

import com.dalai.llama.tenant.showcase.config.ShowcaseProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HandlePolicyTest {

    private final HandlePolicy policy = new HandlePolicy(new ShowcaseProperties(
            "https://dalaillama.in", 30,
            new ShowcaseProperties.Profile(30, 90, 3, List.of("admin", "creators", "Support")),
            new ShowcaseProperties.Picks(2, 6),
            new ShowcaseProperties.Funding(true, 5, 1, 1),
            new ShowcaseProperties.PlatformMatch(1.5, "Made on Dalaillama")));

    @Test
    void normalize_lowercasesDropsAccentsAndCollapsesSeparators() {
        assertThat(policy.normalize("  Akash Tripathi Films!! ")).isEqualTo("akash-tripathi-films");
        assertThat(policy.normalize("Zoë & Renée")).isEqualTo("zoe-renee");
        assertThat(policy.normalize("--hello__world--")).isEqualTo("hello-world");
        assertThat(policy.normalize(null)).isEmpty();
    }

    @Test
    void normalize_cutsToMaxLengthWithoutATrailingHyphen() {
        String name = "a".repeat(39) + " b";
        String handle = policy.normalize(name);
        assertThat(handle).hasSizeLessThanOrEqualTo(HandlePolicy.MAX_LENGTH).doesNotEndWith("-");
    }

    @Test
    void baseFromName_fallsBackWhenTheNameGivesNoUsableHandle() {
        assertThat(policy.baseFromName("Riya Motion")).isEqualTo("riya-motion");
        assertThat(policy.baseFromName("नमस्ते")).isEqualTo(HandlePolicy.FALLBACK_BASE);
        assertThat(policy.baseFromName("AB")).isEqualTo(HandlePolicy.FALLBACK_BASE);
        assertThat(policy.baseFromName("Admin")).isEqualTo(HandlePolicy.FALLBACK_BASE);
        assertThat(policy.baseFromName(null)).isEqualTo(HandlePolicy.FALLBACK_BASE);
    }

    @Test
    void candidate_appendsANumberAndStillFits() {
        assertThat(policy.candidate("riya", 1)).isEqualTo("riya");
        assertThat(policy.candidate("riya", 2)).isEqualTo("riya-2");
        String long40 = "x".repeat(40);
        assertThat(policy.candidate(long40, 12)).hasSize(40).endsWith("-12");
    }

    @Test
    void problem_reportsEachRule() {
        assertThat(policy.problem("ab")).contains(HandlePolicy.Problem.TOO_SHORT);
        assertThat(policy.problem("a".repeat(41))).contains(HandlePolicy.Problem.TOO_LONG);
        assertThat(policy.problem("Riya")).contains(HandlePolicy.Problem.INVALID_FORMAT);
        assertThat(policy.problem("-riya")).contains(HandlePolicy.Problem.INVALID_FORMAT);
        assertThat(policy.problem("riya motion")).contains(HandlePolicy.Problem.INVALID_FORMAT);
        assertThat(policy.problem("support")).contains(HandlePolicy.Problem.RESERVED);
        assertThat(policy.problem("riya-motion-2")).isEmpty();
    }
}
