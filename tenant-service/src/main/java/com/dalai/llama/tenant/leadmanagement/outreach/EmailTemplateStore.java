package com.dalai.llama.tenant.leadmanagement.outreach;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** {@code lead_email_template} (V34). tenant_id NULL = a global template every creator can use. */
@Repository
@RequiredArgsConstructor
public class EmailTemplateStore {

    public record EmailTemplate(UUID id, UUID tenantId, String name, OutreachTemplate layout, String subject, String intro,
                                boolean active) {
        public boolean global() {
            return tenantId == null;
        }
    }

    private static final String COLUMNS = "id, tenant_id, name, layout, subject, intro, active";
    private static final RowMapper<EmailTemplate> ROW = (rs, n) -> new EmailTemplate(rs.getObject(1, UUID.class),
            rs.getObject(2, UUID.class), rs.getString(3), OutreachTemplate.valueOf(rs.getString(4)), rs.getString(5),
            rs.getString(6), rs.getBoolean(7));

    private final JdbcTemplate jdbc;

    /** Active global templates first, then the creator's own. */
    public List<EmailTemplate> usableBy(UUID tenantId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM lead_email_template WHERE active AND (tenant_id IS NULL OR tenant_id = ?)"
                + " ORDER BY tenant_id NULLS FIRST, created_at", ROW, tenantId);
    }

    public List<EmailTemplate> globals() {
        return jdbc.query("SELECT " + COLUMNS + " FROM lead_email_template WHERE tenant_id IS NULL ORDER BY created_at", ROW);
    }

    public Optional<EmailTemplate> find(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM lead_email_template WHERE id = ?", ROW, id).stream().findFirst();
    }

    /** The oldest active global template with this layout: the built-in copy for sends that name no template. */
    public Optional<EmailTemplate> defaultFor(OutreachTemplate layout) {
        return jdbc.query("SELECT " + COLUMNS + " FROM lead_email_template WHERE tenant_id IS NULL AND active AND layout = ?"
                + " ORDER BY created_at LIMIT 1", ROW, layout.name()).stream().findFirst();
    }

    public EmailTemplate insert(UUID tenantId, String name, OutreachTemplate layout, String subject, String intro) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO lead_email_template (id, tenant_id, name, layout, subject, intro) VALUES (?, ?, ?, ?, ?, ?)",
                id, tenantId, name, layout.name(), subject, intro);
        return find(id).orElseThrow();
    }

    public EmailTemplate update(UUID id, String name, OutreachTemplate layout, String subject, String intro, boolean active) {
        jdbc.update("UPDATE lead_email_template SET name = ?, layout = ?, subject = ?, intro = ?, active = ?, updated_at = NOW()"
                + " WHERE id = ?", name, layout.name(), subject, intro, active, id);
        return find(id).orElseThrow();
    }
}
