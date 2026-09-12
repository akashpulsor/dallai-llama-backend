package com.dalai.llama.preprod.repository;

import com.dalai.llama.preprod.domain.entity.CastAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CastAssignmentRepository extends JpaRepository<CastAssignment, UUID> {

    List<CastAssignment> findByProjectId(UUID projectId);

    Optional<CastAssignment> findByProjectIdAndScriptCharacterId(UUID projectId, UUID scriptCharacterId);

    boolean existsByProjectIdAndCastProfileId(UUID projectId, UUID castProfileId);

    /** How many distinct projects a cast profile has actually been cast into -- the Cast Library's
     * "used in N projects" count. Distinct on project id since the same profile can be assigned to
     * more than one character within the same project (a narrator reusing an on-screen actor). */
    @Query("select ca.castProfileId as castProfileId, count(distinct ca.projectId) as projectCount "
            + "from CastAssignment ca where ca.castProfileId in :castProfileIds group by ca.castProfileId")
    List<CastProfileProjectCount> countDistinctProjectsByCastProfileIdIn(@Param("castProfileIds") List<UUID> castProfileIds);

    interface CastProfileProjectCount {
        UUID getCastProfileId();
        long getProjectCount();
    }
}
