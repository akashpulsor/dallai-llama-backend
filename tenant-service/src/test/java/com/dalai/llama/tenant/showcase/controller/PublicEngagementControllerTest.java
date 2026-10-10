package com.dalai.llama.tenant.showcase.controller;

import com.dalai.llama.tenant.domain.exception.GlobalExceptionHandler;
import com.dalai.llama.tenant.leadmanagement.brand.BrandProperties;
import com.dalai.llama.tenant.leadmanagement.brand.BrandSessionService;
import com.dalai.llama.tenant.leadmanagement.inquiry.BrandInquiryService;
import com.dalai.llama.tenant.showcase.service.FollowService;
import com.dalai.llama.tenant.showcase.service.ShowcaseEngagementService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Likes need only a visitor id; follows and requests need a real brand session cookie (401 otherwise). */
class PublicEngagementControllerTest {

    private final ShowcaseEngagementService engagement = mock(ShowcaseEngagementService.class);
    private final FollowService follows = mock(FollowService.class);
    private final BrandInquiryService inquiries = mock(BrandInquiryService.class);
    private final BrandSessionService sessions = new BrandSessionService(
            new BrandProperties("secret", 30, true, 30, 5, "", "", ""), Clock.fixed(Instant.parse("2026-10-09T10:00:00Z"), ZoneOffset.UTC));
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new PublicEngagementController(engagement, follows, inquiries, sessions))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void anonymousVisitorsCanLike() throws Exception {
        UUID visitor = UUID.randomUUID();
        when(engagement.setLiked("pub0000001", visitor, true)).thenReturn(new ShowcaseEngagementService.LikeState(true, 4));
        mvc.perform(put("/api/v1/public/showcase/pub0000001/like").header("X-Visitor-Id", visitor.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.likeCount").value(4));
    }

    @Test
    void followingWithoutASessionIs401() throws Exception {
        mvc.perform(put("/api/v1/public/creators/riya-motion/follow")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("BRAND_SIGN_IN_REQUIRED"));
        mvc.perform(put("/api/v1/public/creators/riya-motion/follow").cookie(new Cookie(BrandSessionService.COOKIE, "v1.forged.1.x")))
                .andExpect(status().isUnauthorized());
        verify(follows, never()).follow(any(), any());
    }

    @Test
    void aSignedInBrandCanFollowAndRequest() throws Exception {
        UUID brand = UUID.randomUUID();
        String session = sessions.issue(brand).token();
        when(follows.follow(brand, "riya-motion")).thenReturn(new FollowService.FollowState(true, 1));
        mvc.perform(put("/api/v1/public/creators/riya-motion/follow").cookie(new Cookie(BrandSessionService.COOKIE, session)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.followerCount").value(1));

        mvc.perform(post("/api/v1/public/creators/riya-motion/inquiries").cookie(new Cookie(BrandSessionService.COOKIE, session))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/public/creators/riya-motion/inquiries").cookie(new Cookie(BrandSessionService.COOKIE, session))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"A launch film\",\"budgetBand\":\"NOT_SURE\"}"))
                .andExpect(status().isCreated());
        verify(inquiries).submit(eq(brand), eq("riya-motion"), any());
    }
}
