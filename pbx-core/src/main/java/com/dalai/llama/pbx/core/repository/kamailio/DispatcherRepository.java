package com.dalai.llama.pbx.core.repository.kamailio;


import com.dalai.llama.pbx.core.domain.entity.kamailio.Dispatcher;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Kamailio dispatcher table — FreeSWITCH load balancing destinations.
 *
 * setid=1 is the default FreeSWITCH group (shared infrastructure).
 * Dedicated tenants may get their own setid.
 *
 * Kamailio ds_select_dst() reads this directly.
 * After INSERT, tenant-service calls /api/v1/write/kamailio/reload
 * which triggers kamcmd dispatcher.reload.
 */
@Repository
public interface DispatcherRepository extends JpaRepository<Dispatcher, Integer> {

    List<Dispatcher> findBySetid(Integer setid);

    Optional<Dispatcher> findBySetidAndDestination(Integer setid, String destination);

    boolean existsBySetidAndDestination(Integer setid, String destination);

    List<Dispatcher> findByTenantId(UUID tenantId);

    @Modifying
    void deleteByTenantId(UUID tenantId);
}