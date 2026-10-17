package com.dalai.llama.tenant.showcase.inspiration;

import com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class InspirationStore {

    private static final RowMapper<InspirationItem> ROW = (rs, n) -> new InspirationItem(
            rs.getObject("id", UUID.class), rs.getString("pin_id"), InspirationPins.url(rs.getString("pin_id")),
            rs.getString("title"), ShowcaseIndustry.valueOf(rs.getString("industry")), rs.getString("note"));

    private final JdbcTemplate jdbc;

    /** Active items, one industry or all, in ops' order. */
    public List<InspirationItem> active(ShowcaseIndustry industry) {
        return industry == null
                ? jdbc.query("SELECT * FROM inspiration_item WHERE active ORDER BY industry, sort_order, created_at", ROW)
                : jdbc.query("SELECT * FROM inspiration_item WHERE active AND industry = ? ORDER BY sort_order, created_at",
                        ROW, industry.name());
    }

    /** Adds the pin, or updates its labels and shows it again when it is already on the board. */
    public InspirationItem save(String pinId, String title, ShowcaseIndustry industry, String note, int sortOrder) {
        jdbc.update("""
                INSERT INTO inspiration_item (id, pin_id, title, industry, note, sort_order) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (pin_id) DO UPDATE SET title = EXCLUDED.title, industry = EXCLUDED.industry,
                    note = EXCLUDED.note, sort_order = EXCLUDED.sort_order, active = TRUE""",
                UUID.randomUUID(), pinId, title, industry.name(), note, sortOrder);
        return jdbc.queryForObject("SELECT * FROM inspiration_item WHERE pin_id = ?", ROW, pinId);
    }

    public boolean hide(UUID id) {
        return jdbc.update("UPDATE inspiration_item SET active = FALSE WHERE id = ?", id) == 1;
    }
}
