package com.dalai.llama.tenant.leadmanagement.creatormail;

import com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** The weekly brand follow-ups' switch and templates (V77), and what each mailed brand has done so far. */
@Repository
@RequiredArgsConstructor
public class BrandFollowUpStore {

    /** How far a brand has got with the mail it was sent, and the last mail each kind of brand gets. */
    public enum Stage { NOT_OPENED, OPENED, CLICKED, LAST_NOT_OPENED, LAST_OPENED }

    /** A brand that was sent brand mail. {@code followUps}: how many of its mails were follow-ups.
     * {@code priorityThemes}: what it asked for in its console (comma separated), null if it hasn't said. */
    public record MailedBrand(String email, String name, String company, ShowcaseIndustry industry, String topics, String recipientHash,
                              OffsetDateTime lastSentAt, int followUps, boolean opened, boolean clicked, boolean requested,
                              boolean unsubscribed, boolean badAddress, boolean queued, String priorityThemes) {
        Stage stage() {
            if (clicked) return Stage.CLICKED;
            return opened ? Stage.OPENED : Stage.NOT_OPENED;
        }

        Stage lastMail() {
            return opened || clicked ? Stage.LAST_OPENED : Stage.LAST_NOT_OPENED;
        }

        boolean gavePriorities() {
            return priorityThemes != null;
        }
    }

    private final JdbcTemplate jdbc;

    public boolean enabled() {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT enabled FROM ops_mail_follow_up WHERE id = 1", Boolean.class));
    }

    public void setEnabled(boolean enabled) {
        jdbc.update("UPDATE ops_mail_follow_up SET enabled = ?, updated_at = NOW() WHERE id = 1", enabled);
    }

    /** The templates a stage works through, in order; {@code activeOnly} leaves out those switched off. */
    public List<UUID> templates(Stage stage, boolean activeOnly) {
        return jdbc.queryForList("""
                SELECT f.template_id FROM ops_mail_follow_up_template f JOIN ops_creator_mail_template t ON t.id = f.template_id
                WHERE f.stage = ? AND (t.active OR NOT ?) ORDER BY f.position""", UUID.class, stage.name(), activeOnly);
    }

    public void replaceTemplates(Stage stage, List<UUID> templateIds) {
        jdbc.update("DELETE FROM ops_mail_follow_up_template WHERE stage = ?", stage.name());
        for (int i = 0; i < templateIds.size(); i++) {
            jdbc.update("INSERT INTO ops_mail_follow_up_template (stage, template_id, position) VALUES (?, ?, ?)", stage.name(), templateIds.get(i), i);
        }
    }

    public List<MailedBrand> mailedBrands() {
        return jdbc.query("""
                SELECT d.email, (ARRAY_AGG(d.name ORDER BY d.sent_at DESC))[1], (ARRAY_AGG(d.company ORDER BY d.sent_at DESC))[1],
                       (ARRAY_AGG(d.industry ORDER BY d.sent_at DESC))[1], (ARRAY_AGG(d.topics ORDER BY d.sent_at DESC))[1], MAX(d.recipient_hash),
                       MAX(d.sent_at), COUNT(*) FILTER (WHERE s.audience = 'FOLLOWUP'), COUNT(d.opened_at) > 0, COUNT(d.first_clicked_at) > 0,
                       EXISTS (SELECT 1 FROM lead_brand_inquiry i JOIN lead_brand_contact c ON c.id = i.brand_contact_id WHERE LOWER(c.email) = d.email),
                       EXISTS (SELECT 1 FROM lead_suppression x WHERE x.recipient_hash = MAX(d.recipient_hash)),
                       EXISTS (SELECT 1 FROM lead_contact_point cp WHERE cp.kind = 'EMAIL' AND LOWER(cp.value) = d.email AND cp.status = 'INVALID'),
                       EXISTS (SELECT 1 FROM ops_creator_mail_delivery q WHERE q.email = d.email AND q.status = 'QUEUED'),
                       (SELECT p.themes FROM lead_brand_mail_priority p JOIN lead_brand_contact c ON c.id = p.brand_contact_id WHERE LOWER(c.email) = d.email)
                FROM ops_creator_mail_delivery d JOIN ops_creator_mail_template t ON t.id = d.template_id
                JOIN ops_creator_mail_send s ON s.id = d.send_id
                WHERE t.mail_for = 'BRANDS' AND d.status = 'SENT' GROUP BY d.email""",
                (rs, n) -> new MailedBrand(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4) == null ? null : ShowcaseIndustry.valueOf(rs.getString(4)), rs.getString(5), rs.getString(6),
                        rs.getObject(7, OffsetDateTime.class), rs.getInt(8), rs.getBoolean(9), rs.getBoolean(10), rs.getBoolean(11),
                        rs.getBoolean(12), rs.getBoolean(13), rs.getBoolean(14), rs.getString(15)));
    }

    /** Takes back weekly mails that haven't gone yet. */
    public int cancelQueued() {
        return jdbc.update("""
                DELETE FROM ops_creator_mail_delivery d USING ops_creator_mail_send s
                WHERE s.id = d.send_id AND s.audience = 'FOLLOWUP' AND d.status = 'QUEUED'""");
    }
}
