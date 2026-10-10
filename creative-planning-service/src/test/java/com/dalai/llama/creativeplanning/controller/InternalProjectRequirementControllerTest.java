package com.dalai.llama.creativeplanning.controller;

import com.dalai.llama.creativeplanning.dto.CreateStandaloneRequirementRequest;
import com.dalai.llama.creativeplanning.dto.ProjectRequirementView;
import com.dalai.llama.creativeplanning.domain.TenantType;
import com.dalai.llama.creativeplanning.service.requirement.ProjectRequirementService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InternalProjectRequirementControllerTest {

    private final ProjectRequirementService service = mock(ProjectRequirementService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new InternalProjectRequirementController(service)).build();

    @Test
    void createsAStandaloneBriefForTheTenantInThePathWithNoUser() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID requirementId = UUID.randomUUID();
        ProjectRequirementView created = mock(ProjectRequirementView.class);
        when(created.id()).thenReturn(requirementId);
        when(created.shareToken()).thenReturn("share-123");
        when(service.createStandalone(eq(tenant), isNull(), any())).thenReturn(created);

        mvc.perform(post("/api/v1/internal/tenants/{tenantId}/project-requirements/standalone", tenant)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"briefText":"A 30-second launch film","durationSeconds":30,"languages":["English"],
                                 "tenantType":"AI_VIDEO_CREATOR","brandContext":{"brandName":"Hearth Foods","industry":"Food & beverage"}}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.requirementId").value(requirementId.toString()))
                .andExpect(jsonPath("$.shareToken").value("share-123"));

        ArgumentCaptor<CreateStandaloneRequirementRequest> request = ArgumentCaptor.forClass(CreateStandaloneRequirementRequest.class);
        verify(service).createStandalone(eq(tenant), isNull(), request.capture());
        assertThat(request.getValue().tenantType()).isEqualTo(TenantType.AI_VIDEO_CREATOR);
        assertThat(request.getValue().brandContext().brandName()).isEqualTo("Hearth Foods");
    }

    @Test
    void aBriefWithoutTextIsRejected() throws Exception {
        mvc.perform(post("/api/v1/internal/tenants/{tenantId}/project-requirements/standalone", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"durationSeconds\":30,\"languages\":[\"English\"],\"tenantType\":\"AI_VIDEO_CREATOR\"}"))
                .andExpect(status().isBadRequest());
        verify(service, never()).createStandalone(any(), any(), any());
    }
}
