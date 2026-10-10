package com.dalai.llama.tenant.showcase.controller;

import com.dalai.llama.tenant.domain.exception.GlobalExceptionHandler;
import com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry;
import com.dalai.llama.tenant.showcase.dto.ShowcaseFeedPage;
import com.dalai.llama.tenant.showcase.service.PublicShowcaseService;
import com.dalai.llama.tenant.showcase.service.ShowcaseEngagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP shape of the public endpoints: status codes for bad input, and parameter binding. */
class PublicShowcaseControllerTest {

    private PublicShowcaseService showcaseService;
    private ShowcaseEngagementService engagementService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        showcaseService = mock(PublicShowcaseService.class);
        engagementService = mock(ShowcaseEngagementService.class);
        mvc = MockMvcBuilders.standaloneSetup(new PublicShowcaseController(showcaseService, engagementService,
                        mock(com.dalai.llama.tenant.showcase.service.ShowcaseModerationService.class),
                        mock(com.dalai.llama.tenant.showcase.service.LandingAssembler.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void unknownHandleIs404() throws Exception {
        when(showcaseService.profile("nobody")).thenReturn(Optional.empty());
        mvc.perform(get("/api/v1/public/creators/nobody")).andExpect(status().isNotFound());
    }

    @Test
    void feedBindsFiltersAndRejectsAnUnknownIndustry() throws Exception {
        when(showcaseService.feed(PublicShowcaseService.FeedTab.TOP, ShowcaseIndustry.FOOD_BEVERAGE, null, 2)).thenReturn(new ShowcaseFeedPage(List.of(), 2, false));
        mvc.perform(get("/api/v1/public/showcase").param("tab", "TOP").param("industry", "FOOD_BEVERAGE").param("page", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2));
        mvc.perform(get("/api/v1/public/showcase").param("industry", "SPACESHIPS")).andExpect(status().isBadRequest());
    }

    @Test
    void playNeedsAVisitorId_andUnknownVideosAre400() throws Exception {
        UUID visitor = UUID.randomUUID();
        mvc.perform(post("/api/v1/public/showcase/abc/plays").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/public/showcase/abc/plays").header("X-Visitor-Id", "not-a-uuid"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/v1/public/showcase/abc/plays").header("X-Visitor-Id", visitor.toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"completed\":true}"))
                .andExpect(status().isNoContent());
        verify(engagementService).recordPlay("abc", visitor, true);

        doThrow(new IllegalArgumentException("Unknown video")).when(engagementService).recordPlay(eq("gone"), any(), eq(false));
        mvc.perform(post("/api/v1/public/showcase/gone/plays").header("X-Visitor-Id", visitor.toString()))
                .andExpect(status().isBadRequest());
    }
}
