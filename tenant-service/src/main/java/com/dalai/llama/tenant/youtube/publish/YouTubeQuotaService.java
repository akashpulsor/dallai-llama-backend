package com.dalai.llama.tenant.youtube.publish;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/** Rule 41: the YouTube Data API quota is per Google Cloud project, so every creator's upload draws
 * on one shared daily budget (10,000 units by default; an upload costs ~1,600). Work is only
 * started when the budget can pay for it; what can't wait stays queued until the daily reset
 * (midnight Pacific, when Google resets it). Ops see and adjust the budget. */
@Service
public class YouTubeQuotaService {

    static final ZoneId YOUTUBE_DAY = ZoneId.of("America/Los_Angeles");
    /** thumbnails.set and videos.update each cost 50 units. */
    public static final int EDIT_UNITS = 50;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final int defaultDailyLimit;
    private final int defaultUploadUnits;

    public YouTubeQuotaService(JdbcTemplate jdbc, Clock clock,
                               @Value("${youtube-quota.daily-limit:10000}") int defaultDailyLimit,
                               @Value("${youtube-quota.upload-units:1600}") int defaultUploadUnits) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.defaultDailyLimit = defaultDailyLimit;
        this.defaultUploadUnits = defaultUploadUnits;
    }

    public record Budget(int dailyLimit, int uploadUnits) {
    }

    public record QuotaView(LocalDate day, int unitsUsed, int dailyLimit, int uploadUnits, int uploadsToday, int uploadsLeftToday,
                            Instant resetsAt, int waitingJobs, int uploadingJobs) {
    }

    /** Takes {@code units} from today's budget if they fit; false (nothing taken) otherwise. */
    @Transactional
    public boolean tryReserve(int units, boolean upload) {
        Budget budget = budget();
        Date day = Date.valueOf(today());
        jdbc.update("INSERT INTO youtube_quota_usage (quota_day) VALUES (?) ON CONFLICT DO NOTHING", day);
        return jdbc.update("""
                UPDATE youtube_quota_usage SET units_used = units_used + ?, uploads = uploads + ?, updated_at = NOW()
                WHERE quota_day = ? AND units_used + ? <= ?""",
                units, upload ? 1 : 0, day, units, budget.dailyLimit()) == 1;
    }

    public boolean tryReserveUpload() {
        return tryReserve(budget().uploadUnits(), true);
    }

    /** When YouTube resets the quota: the next midnight Pacific. */
    public Instant nextReset() {
        return today().plusDays(1).atStartOfDay(YOUTUBE_DAY).toInstant();
    }

    public Budget budget() {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT daily_limit, upload_units FROM youtube_quota_setting WHERE id = 1");
        if (rows.isEmpty()) return new Budget(defaultDailyLimit, defaultUploadUnits);
        return new Budget(((Number) rows.get(0).get("daily_limit")).intValue(), ((Number) rows.get(0).get("upload_units")).intValue());
    }

    @Transactional
    public QuotaView setBudget(int dailyLimit, int uploadUnits) {
        if (dailyLimit < 100 || uploadUnits < 1 || uploadUnits > dailyLimit) throw new IllegalArgumentException("Invalid quota numbers");
        jdbc.update("""
                INSERT INTO youtube_quota_setting (id, daily_limit, upload_units) VALUES (1, ?, ?)
                ON CONFLICT (id) DO UPDATE SET daily_limit = EXCLUDED.daily_limit, upload_units = EXCLUDED.upload_units, updated_at = NOW()""",
                dailyLimit, uploadUnits);
        return view();
    }

    public QuotaView view() {
        Budget budget = budget();
        LocalDate day = today();
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT units_used, uploads FROM youtube_quota_usage WHERE quota_day = ?", Date.valueOf(day));
        int used = rows.isEmpty() ? 0 : ((Number) rows.get(0).get("units_used")).intValue();
        int uploads = rows.isEmpty() ? 0 : ((Number) rows.get(0).get("uploads")).intValue();
        Integer waiting = jdbc.queryForObject("SELECT COUNT(*) FROM youtube_publish_job WHERE status = 'QUEUED'", Integer.class);
        Integer uploading = jdbc.queryForObject("SELECT COUNT(*) FROM youtube_publish_job WHERE status = 'UPLOADING'", Integer.class);
        return new QuotaView(day, used, budget.dailyLimit(), budget.uploadUnits(), uploads,
                Math.max(0, (budget.dailyLimit() - used) / budget.uploadUnits()), nextReset(),
                waiting == null ? 0 : waiting, uploading == null ? 0 : uploading);
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(YOUTUBE_DAY));
    }
}
