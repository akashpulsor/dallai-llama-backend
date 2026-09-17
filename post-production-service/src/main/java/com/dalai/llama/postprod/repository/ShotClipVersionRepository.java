package com.dalai.llama.postprod.repository;

import com.dalai.llama.postprod.domain.ClipVersionStatus;
import com.dalai.llama.postprod.domain.entity.ShotClipVersion;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShotClipVersionRepository extends JpaRepository<ShotClipVersion, UUID> {

    /** Every cut of one shot, newest first. The page's per-card read. */
    List<ShotClipVersion> findByShotIdOrderByVersionNumberDesc(UUID shotId);

    Optional<ShotClipVersion> findByShotIdAndStatus(UUID shotId, ClipVersionStatus status);

    /**
     * The current cut of every shot in a project, in one query.
     *
     * <p>Film assembly and the project-wide listing both need this. Asking per shot turned a page
     * load into one query per card, which is the shape that made the video tab slow in the first
     * place.
     */
    List<ShotClipVersion> findByProjectIdAndStatus(UUID projectId, ClipVersionStatus status);

    /**
     * Locks the shot's rows before a version number is chosen or an accept is applied.
     *
     * <p>Both operations read-then-write: "what is the highest version so far" and "which row is
     * ACTIVE". Two requests interleaving there produce either a duplicate version number or two
     * ACTIVE rows, and while the unique indexes would reject the second write, a rejected write
     * surfaces as a constraint violation rather than the queue behaviour a creator expects. Taking
     * the lock first makes the second request wait and then read the truth.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from ShotClipVersion v where v.shotId = :shotId")
    List<ShotClipVersion> lockByShotId(@Param("shotId") UUID shotId);

    @Query("select coalesce(max(v.versionNumber), 0) from ShotClipVersion v where v.shotId = :shotId")
    int highestVersionNumber(@Param("shotId") UUID shotId);

    /** The shots of this project a creator has chosen to show a client. */
    List<ShotClipVersion> findByProjectIdAndPublishedIsTrue(UUID projectId);

    long countByProjectIdAndStatus(UUID projectId, ClipVersionStatus status);
}
