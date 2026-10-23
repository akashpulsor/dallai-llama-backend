package com.dalai.llama.tenant.leadmanagement.creatormail;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** {@code ops_creator_mail_template} and {@code ops_creator_mail_send} (V58). */
@Repository
@RequiredArgsConstructor
public class CreatorMailStore {

    public enum Audience { ALL, CHOSEN, TEST }

    public record Template(UUID id, String name, String subject, String body, boolean active, OffsetDateTime lastSentAt) {
    }

    public record Send(UUID id, UUID templateId, String subject, Audience audience, int recipients, OffsetDateTime createdAt) {
    }

    private static final String TEMPLATE_SELECT = "SELECT t.id, t.name, t.subject, t.body, t.active,"
            + " (SELECT MAX(s.created_at) FROM ops_creator_mail_send s WHERE s.template_id = t.id AND s.audience <> 'TEST')"
            + " FROM ops_creator_mail_template t";
    private static final RowMapper<Template> TEMPLATE = (rs, n) -> new Template(rs.getObject(1, UUID.class), rs.getString(2),
            rs.getString(3), rs.getString(4), rs.getBoolean(5), rs.getObject(6, OffsetDateTime.class));
    private static final RowMapper<Send> SEND = (rs, n) -> new Send(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
            rs.getString(3), Audience.valueOf(rs.getString(4)), rs.getInt(5), rs.getObject(6, OffsetDateTime.class));

    private final JdbcTemplate jdbc;

    public List<Template> templates() {
        return jdbc.query(TEMPLATE_SELECT + " ORDER BY t.active DESC, t.updated_at DESC", TEMPLATE);
    }

    public Optional<Template> find(UUID id) {
        return jdbc.query(TEMPLATE_SELECT + " WHERE t.id = ?", TEMPLATE, id).stream().findFirst();
    }

    public Template insert(String name, String subject, String body) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO ops_creator_mail_template (id, name, subject, body) VALUES (?, ?, ?, ?)", id, name, subject, body);
        return find(id).orElseThrow();
    }

    public Optional<Template> update(UUID id, String name, String subject, String body, boolean active) {
        jdbc.update("UPDATE ops_creator_mail_template SET name = ?, subject = ?, body = ?, active = ?, updated_at = NOW() WHERE id = ?",
                name, subject, body, active, id);
        return find(id);
    }

    public void logSend(UUID templateId, String subject, Audience audience, int recipients) {
        jdbc.update("INSERT INTO ops_creator_mail_send (id, template_id, subject, audience, recipients) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), templateId, subject, audience.name(), recipients);
    }

    public List<Send> recentSends(int limit) {
        return jdbc.query("SELECT id, template_id, subject, audience, recipients, created_at FROM ops_creator_mail_send"
                + " ORDER BY created_at DESC LIMIT ?", SEND, limit);
    }
}
