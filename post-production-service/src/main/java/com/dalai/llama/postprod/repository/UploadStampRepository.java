package com.dalai.llama.postprod.repository;

import com.dalai.llama.postprod.domain.entity.UploadStamp;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface UploadStampRepository extends JpaRepository<UploadStamp, UUID> {

    /** Uploads due a stamp: never asked, stuck in PENDING, or FAILED with attempts left -- the same
     * rule as films (FilmRenderRepository.findDueForStamp). */
    @Query(value = """
            SELECT * FROM upload_stamp u
            WHERE u.stamped_object_key IS NULL
              AND (u.status IS NULL
                   OR (u.status = 'PENDING' AND u.requested_at < :stuckBefore)
                   OR (u.status = 'FAILED' AND u.attempts < :maxAttempts AND u.requested_at < :retryBefore))
            ORDER BY u.created_at
            LIMIT :limit""", nativeQuery = true)
    List<UploadStamp> findDue(@Param("stuckBefore") OffsetDateTime stuckBefore,
                              @Param("retryBefore") OffsetDateTime retryBefore,
                              @Param("maxAttempts") int maxAttempts,
                              @Param("limit") int limit);
}
