package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorProject;
import com.dalai.llama.creator.domain.entity.CreatorScript;
import com.dalai.llama.creator.repository.CreatorProjectRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CreatorCreativeLearningServiceTest {

    @Test
    void storesOnlyGeneralizedApprovedRulesAndMakesThemRetrievable() {
        CreatorProjectRepository projectRepository = mock(CreatorProjectRepository.class);
        CreatorCreativeLearningService service = new CreatorCreativeLearningService(
                projectRepository,
                new ObjectMapper()
        );
        UUID projectId = UUID.randomUUID();
        CreatorProject project = CreatorProject.builder()
                .id(projectId)
                .tenantId("tenant-1")
                .userId("user-1")
                .memorySnapshot(new LinkedHashMap<>())
                .build();
        CreatorScript script = CreatorScript.builder()
                .id(UUID.randomUUID())
                .projectId(projectId)
                .tenantId("tenant-1")
                .userId("user-1")
                .categoryCode("food")
                .scriptPayload(new LinkedHashMap<>(Map.of(
                        "productIntelligenceBrief", Map.of(
                                "productName", "Mmmelt Noir",
                                "productCategory", "chocolate"
                        ),
                        "creativeDirection", Map.of("adFormat", "product_showcase")
                )))
                .build();
        when(projectRepository.findByIdAndTenantIdAndUserId(projectId, "tenant-1", "user-1"))
                .thenReturn(Optional.of(project));
        when(projectRepository.save(any(CreatorProject.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Map<String, Object> summary = service.recordApprovedReview(
                script,
                List.of(Map.of(
                        "principle", "Let Mmmelt Noir emerge through an ordered mystery-to-craft-to-hero reveal https://reference.test/frame.png " + "\u2B50",
                        "appliesWhen", "premium chocolate product showcase",
                        "avoid", "repeating the same pour composition",
                        "priority", "HIGH"
                )),
                List.of(1, 2, 3),
                "tenant-1",
                "user-1"
        );

        assertThat(summary).containsEntry("status", "APPROVED").containsEntry("approvedRuleCount", 1);
        String storedMemory = String.valueOf(project.getMemorySnapshot());
        assertThat(storedMemory)
                .doesNotContain("Mmmelt Noir")
                .doesNotContain("https://")
                .doesNotContain("\u2B50")
                .contains("the approved product")
                .contains("mystery-to-craft-to-hero");

        when(projectRepository.findByTenantIdAndUserIdOrderByUpdatedAtDesc(
                eq("tenant-1"),
                eq("user-1"),
                any(Pageable.class)
        )).thenReturn(List.of(project));

        List<Map<String, Object>> guidance = service.approvedGuidance(
                "tenant-1",
                "user-1",
                "food",
                "product_showcase",
                "chocolate"
        );

        assertThat(guidance).singleElement().satisfies(rule -> {
            assertThat(rule.get("principle")).asString().contains("the approved product");
            assertThat(rule.get("relevanceScore")).isEqualTo(13);
        });
    }
}
