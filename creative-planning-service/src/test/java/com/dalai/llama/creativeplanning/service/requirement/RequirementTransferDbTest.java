package com.dalai.llama.creativeplanning.service.requirement;

import com.dalai.llama.creativeplanning.service.CreativePlanningException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A brief Dalaillama made for a brand's request, handed to the creator who took it, on a real Postgres. */
@Testcontainers(disabledWithoutDocker = true)
@JdbcTest(properties = "spring.flyway.enabled=true")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(RequirementTransferService.class)
class RequirementTransferDbTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private RequirementTransferService transfers;
    @Autowired private JdbcTemplate jdbc;

    private final UUID house = UUID.randomUUID();
    private final UUID creator = UUID.randomUUID();

    @Test
    void theBriefItsImagesAndProductMoveToTheCreator_withTheirOwnCopyOfTheBrand() {
        UUID brand = brand(house);
        UUID brief = brief(house, brand);
        UUID product = UUID.randomUUID();
        jdbc.update("INSERT INTO product_profile (id, tenant_id, brand_context_id, name, project_requirement_id) VALUES (?, ?, ?, 'Mango pickle', ?)",
                product, house, brand, brief);
        UUID productImage = UUID.randomUUID();
        jdbc.update("INSERT INTO product_reference_image (id, tenant_id, product_profile_id, bucket, object_key) VALUES (?, ?, ?, 'b', 'p1')",
                productImage, house, product);
        jdbc.update("INSERT INTO reference_image_analysis (tenant_id, reference_image_id, description) VALUES (?, ?, 'a jar')", house, productImage);
        UUID moodImage = UUID.randomUUID();
        jdbc.update("INSERT INTO project_reference_image (id, tenant_id, project_requirement_id, bucket, object_key) VALUES (?, ?, ?, 'b', 'm1')",
                moodImage, house, brief);
        jdbc.update("INSERT INTO project_reference_image_analysis (tenant_id, reference_image_id, description) VALUES (?, ?, 'warm')", house, moodImage);
        // Another brief of Dalaillama's for the same brand keeps the original.
        UUID other = brief(house, brand);

        transfers.transfer(house, brief, creator);

        assertThat(owner("project_requirement", "id", brief)).isEqualTo(creator);
        UUID copy = jdbc.queryForObject("SELECT brand_context_id FROM project_requirement WHERE id = ?", UUID.class, brief);
        assertThat(copy).isNotEqualTo(brand);
        assertThat(jdbc.queryForMap("SELECT tenant_id, brand_name FROM brand_context WHERE id = ?", copy))
                .containsEntry("tenant_id", creator).containsEntry("brand_name", "Ghar Pickles");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM brand_context_version WHERE brand_context_id = ? AND version = 1", Integer.class, copy))
                .isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT tenant_id, brand_context_id FROM product_profile WHERE id = ?", product))
                .containsEntry("tenant_id", creator).containsEntry("brand_context_id", copy);
        assertThat(owner("product_reference_image", "id", productImage)).isEqualTo(creator);
        assertThat(owner("reference_image_analysis", "reference_image_id", productImage)).isEqualTo(creator);
        assertThat(owner("project_reference_image", "id", moodImage)).isEqualTo(creator);
        assertThat(owner("project_reference_image_analysis", "reference_image_id", moodImage)).isEqualTo(creator);
        assertThat(owner("brand_context", "id", brand)).isEqualTo(house);
        assertThat(owner("project_requirement", "id", other)).isEqualTo(house);

        // Asked again (a retry after a timeout): already theirs, nothing changes.
        transfers.transfer(house, brief, creator);
        assertThat(jdbc.queryForObject("SELECT brand_context_id FROM project_requirement WHERE id = ?", UUID.class, brief)).isEqualTo(copy);
    }

    @Test
    void notOnceProductionStarted_norFromAnAccountThatDoesntHoldIt() {
        UUID started = brief(house, brand(house));
        jdbc.update("INSERT INTO locked_idea (tenant_id, project_requirement_id, title, budget_tier) VALUES (?, ?, 'Idea', 'LEAN')", house, started);
        assertThatThrownBy(() -> transfers.transfer(house, started, creator))
                .isInstanceOf(CreativePlanningException.class).hasMessageContaining("already started");

        UUID someoneElses = brief(UUID.randomUUID(), null);
        assertThatThrownBy(() -> transfers.transfer(house, someoneElses, creator)).hasMessageContaining("No project requirement");
    }

    private UUID brand(UUID tenant) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO brand_context (id, tenant_id, brand_name, industry) VALUES (?, ?, 'Ghar Pickles', 'food')", id, tenant);
        return id;
    }

    private UUID brief(UUID tenant, UUID brand) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO project_requirement (id, tenant_id, tenant_type, brief_text, budget_tier, share_token, share_token_expires_at,
                                                 brand_context_id, funded)
                VALUES (?, ?, 'AI_VIDEO_CREATOR', 'A 30s reel', 'LEAN', ?, NOW() + INTERVAL '7 days', ?, TRUE)""",
                id, tenant, "t-" + id, brand);
        return id;
    }

    private UUID owner(String table, String column, UUID id) {
        return jdbc.queryForObject("SELECT tenant_id FROM " + table + " WHERE " + column + " = ?", UUID.class, id);
    }
}
