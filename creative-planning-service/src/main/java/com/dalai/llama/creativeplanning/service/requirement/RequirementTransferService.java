package com.dalai.llama.creativeplanning.service.requirement;

import com.dalai.llama.creativeplanning.service.CreativePlanningException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Hands a brief to another creator account before production starts: tenant-service made a
 * brand's request into Dalaillama's own brief so the brand could pay at once, and a creator took
 * it afterwards. The brief, its images and videos, and the product made with it move; the brand
 * is copied for the new owner (the old one may be shared with other briefs). Refused once an idea
 * is locked (production started). Safe to repeat: a brief already with the new owner is left as is.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RequirementTransferService {

    private final JdbcTemplate jdbc;

    @Transactional
    public void transfer(UUID fromTenantId, UUID requirementId, UUID toTenantId) {
        List<UUID> owner = jdbc.queryForList("SELECT tenant_id FROM project_requirement WHERE id = ? FOR UPDATE", UUID.class, requirementId);
        if (owner.isEmpty()) throw CreativePlanningException.notFound("No project requirement " + requirementId);
        if (owner.get(0).equals(toTenantId)) return;
        if (!owner.get(0).equals(fromTenantId)) throw CreativePlanningException.notFound("No project requirement " + requirementId);
        Integer locked = jdbc.queryForObject("SELECT COUNT(*) FROM locked_idea WHERE project_requirement_id = ?", Integer.class, requirementId);
        if (locked != null && locked > 0) throw CreativePlanningException.conflict("Production on this brief has already started");

        UUID brand = jdbc.queryForObject("SELECT brand_context_id FROM project_requirement WHERE id = ?", UUID.class, requirementId);
        UUID brandCopy = brand == null ? null : copyBrand(brand, toTenantId);
        jdbc.update("UPDATE project_requirement SET tenant_id = ?, brand_context_id = ?, updated_at = NOW() WHERE id = ?",
                toTenantId, brandCopy, requirementId);
        jdbc.update("UPDATE project_requirement_attachment SET tenant_id = ? WHERE requirement_id = ?", toTenantId, requirementId);
        jdbc.update("UPDATE project_reference_image SET tenant_id = ? WHERE project_requirement_id = ?", toTenantId, requirementId);
        jdbc.update("""
                UPDATE project_reference_image_analysis SET tenant_id = ?
                WHERE reference_image_id IN (SELECT id FROM project_reference_image WHERE project_requirement_id = ?)""", toTenantId, requirementId);
        jdbc.update("UPDATE project_reference_video SET tenant_id = ? WHERE project_requirement_id = ?", toTenantId, requirementId);
        jdbc.update("UPDATE product_profile SET tenant_id = ?, brand_context_id = COALESCE(?, brand_context_id), updated_at = NOW() "
                + "WHERE project_requirement_id = ?", toTenantId, brandCopy, requirementId);
        jdbc.update("""
                UPDATE product_reference_image SET tenant_id = ?
                WHERE product_profile_id IN (SELECT id FROM product_profile WHERE project_requirement_id = ?)""", toTenantId, requirementId);
        jdbc.update("""
                UPDATE reference_image_analysis SET tenant_id = ?
                WHERE reference_image_id IN (SELECT i.id FROM product_reference_image i
                                             JOIN product_profile p ON p.id = i.product_profile_id WHERE p.project_requirement_id = ?)""",
                toTenantId, requirementId);
        jdbc.update("UPDATE idea_option SET tenant_id = ? WHERE project_requirement_id = ?", toTenantId, requirementId);
        jdbc.update("UPDATE idea_generation_job SET tenant_id = ? WHERE project_requirement_id = ?", toTenantId, requirementId);
        log.info("Brief {} handed from tenant {} to {}", requirementId, fromTenantId, toTenantId);
    }

    /** The brand as the new owner's own, with its first version, as a brand made on a brief would be. */
    private UUID copyBrand(UUID brand, UUID toTenantId) {
        UUID copy = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO brand_context (id, tenant_id, brand_name, industry, brand_voice, target_audience, brand_values, created_at, updated_at)
                SELECT ?, ?, brand_name, industry, brand_voice, target_audience, brand_values, NOW(), NOW() FROM brand_context WHERE id = ?""",
                copy, toTenantId, brand);
        jdbc.update("""
                INSERT INTO brand_context_version (id, tenant_id, brand_context_id, version, brand_name, industry, brand_voice, target_audience,
                                                   brand_values, created_at)
                SELECT gen_random_uuid(), tenant_id, id, 1, brand_name, industry, brand_voice, target_audience, brand_values, created_at
                FROM brand_context WHERE id = ?""", copy);
        return copy;
    }
}
