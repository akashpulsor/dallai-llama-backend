package com.dalai.llama.tenant.leadmanagement.outreach;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** When a recipient's digest goes out: the UTC hour they most often open or click outreach mail
 * over the last {@code sendTime.lookbackDays}, once there are {@code sendTime.minSignals} of them.
 * Without enough of their own, the hour all recipients most often engage; without that, the
 * configured default. A click counts three times an open: some mail apps load images (our open
 * pixel) on delivery, so opens partly echo the send time while clicks are a person reading. */
@Component
@RequiredArgsConstructor
public class OutreachSendTimes {

    static final int CLICK_WEIGHT = 3;

    private final JdbcTemplate jdbc;
    private final OutreachProperties properties;
    private final Clock clock;

    /** The hour for everyone without enough engagement of their own; work it out once per run. */
    public int platformHour() {
        return busiestHour(null).orElse(properties.sendTime().defaultHourUtc());
    }

    public int hourFor(String recipientHash, int platformHour) {
        return busiestHour(recipientHash).orElse(platformHour);
    }

    private Optional<Integer> busiestHour(String recipientHash) {
        String onlyThem = recipientHash == null ? "" : " AND d.recipient_hash = ?";
        String sql = """
                WITH signals AS (
                    SELECT d.first_opened_at AS at, 1 AS weight FROM lead_outreach_delivery d
                    WHERE d.first_opened_at > ?%1$s
                    UNION ALL
                    SELECT l.first_clicked_at, %2$d FROM lead_outreach_link l JOIN lead_outreach_delivery d ON d.id = l.delivery_id
                    WHERE l.first_clicked_at > ?%1$s
                ), hours AS (
                    SELECT EXTRACT(HOUR FROM at AT TIME ZONE 'UTC')::int AS hour, SUM(weight) AS score, COUNT(*) AS signals
                    FROM signals GROUP BY 1
                )
                SELECT hour, SUM(signals) OVER () AS total FROM hours ORDER BY score DESC, hour LIMIT 1""".formatted(onlyThem, CLICK_WEIGHT);
        Timestamp since = Timestamp.from(clock.instant().minus(Duration.ofDays(properties.sendTime().lookbackDays())));
        Object[] args = recipientHash == null ? new Object[]{since, since} : new Object[]{since, recipientHash, since, recipientHash};
        List<int[]> best = jdbc.query(sql, (rs, n) -> new int[]{rs.getInt(1), rs.getInt(2)}, args);
        return best.stream().filter(b -> b[1] >= properties.sendTime().minSignals()).map(b -> b[0]).findFirst();
    }
}
