package com.dalai.llama.tenant.showcase.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.UUID;

/** Per-visitor, per-day play records. Plain SQL because the whole point is Postgres's
 * insert-if-absent and update-if-not-yet semantics, which tell us whether to count. */
@Repository
@RequiredArgsConstructor
public class ShowcasePlayStore {

    private final JdbcTemplate jdbc;

    /** True when this visitor had not played this item yet today. */
    public boolean recordPlay(UUID itemId, UUID visitorId, LocalDate day) {
        return jdbc.update("""
                INSERT INTO showcase_play (showcase_item_id, visitor_id, play_day, completed)
                VALUES (?, ?, ?, FALSE)
                ON CONFLICT (showcase_item_id, visitor_id, play_day) DO NOTHING
                """, itemId, visitorId, day) == 1;
    }

    /** True when this visitor's play today becomes a full play for the first time. */
    public boolean recordFullPlay(UUID itemId, UUID visitorId, LocalDate day) {
        return jdbc.update("""
                UPDATE showcase_play SET completed = TRUE
                WHERE showcase_item_id = ? AND visitor_id = ? AND play_day = ? AND completed = FALSE
                """, itemId, visitorId, day) == 1;
    }
}
