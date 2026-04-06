package com.dalai.llama.pbx.core.repository.kamailio;


import com.dalai.llama.pbx.core.domain.entity.kamailio.UacReg;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Kamailio uacreg table — UAC registrations for outbound trunk auth.
 *
 * Write paths:
 *   - KamailioWriteController → tenant-service inserts during provisioning
 *   - TrunkService → auto-creates uacreg row when SipTrunk is created
 *
 * Kamailio uac module reads this directly for outbound INVITE authentication.
 * After INSERT/UPDATE, trigger kamcmd uac.reg_reload via
 * /api/v1/write/kamailio/reload.
 *
 * l_uuid is the unique key Kamailio uses internally — we set it to
 * trunk_{trunkId} for traceability.
 */
@Repository
public interface UacRegRepository extends JpaRepository<UacReg, Integer> {

    @Query("SELECT u FROM UacReg u WHERE u.lUuid = :lUuid")
    Optional<UacReg> findByLUuid(@Param("lUuid") String lUuid);

    List<UacReg> findByTenantId(UUID tenantId);

    List<UacReg> findByTrunkId(UUID trunkId);

    @Query("SELECT CASE WHEN COUNT(u) > 0 THEN true ELSE false END FROM UacReg u WHERE u.lUuid = :lUuid")
    boolean existsByLUuid(@Param("lUuid") String lUuid);

    @Modifying
    void deleteByTrunkId(UUID trunkId);

    @Modifying
    void deleteByTenantId(UUID tenantId);
}