package com.dalai.llama.preprod.repository;

import com.dalai.llama.preprod.domain.entity.ShotDialogueBeat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShotDialogueBeatRepository extends JpaRepository<ShotDialogueBeat, UUID> {

    List<ShotDialogueBeat> findByShotIdOrderByOrderIndexAsc(UUID shotId);

    Optional<ShotDialogueBeat> findByIdAndTenantId(UUID id, UUID tenantId);

    void deleteByIdAndTenantId(UUID id, UUID tenantId);

    /** Ground truth for "does this character actually speak" -- exactly the same characterKey
     * {@link com.dalai.llama.preprod.service.ShotContextAssemblyService#resolveBeatVoice} resolves
     * a voice for at dispatch time, so this can't disagree with what actually gets dubbed. */
    @Query("SELECT DISTINCT b.characterKey FROM ShotDialogueBeat b WHERE b.shotId IN :shotIds AND b.characterKey IS NOT NULL")
    List<String> findDistinctCharacterKeysByShotIdIn(@Param("shotIds") List<UUID> shotIds);
}
