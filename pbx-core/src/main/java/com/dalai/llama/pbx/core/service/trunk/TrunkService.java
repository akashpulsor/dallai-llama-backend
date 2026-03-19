package com.dalai.llama.pbx.core.service.trunk;


import com.dalai.llama.pbx.core.domain.entity.core.SipTrunk;
import com.dalai.llama.pbx.core.domain.entity.kamailio.UacReg;
import com.dalai.llama.pbx.core.domain.enums.TrunkAuthType;
import com.dalai.llama.pbx.core.domain.enums.TrunkTransport;
import com.dalai.llama.pbx.core.repository.core.SipTrunkRepository;
import com.dalai.llama.pbx.core.repository.kamailio.UacRegRepository;
import com.dalai.llama.pbx.core.service.kamailio.KamailioReloadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SIP trunk lifecycle — the ONE place where sip_trunks and uacreg are kept in sync.
 *
 * Invariant: Every active SipTrunk with auth_type=DIGEST has exactly one uacreg row.
 * Creating a trunk creates the uacreg. Deleting a trunk removes the uacreg.
 * After any uacreg change, kamcmd uac.reg_reload is triggered.
 *
 * Mirrors AgentService pattern (agent↔subscriber sync) but for trunks↔uacreg.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrunkService {

    private final SipTrunkRepository trunkRepository;
    private final UacRegRepository uacRegRepository;
    private final KamailioReloadService kamailioReload;

    // ═══════════════════════════════════════════════════════════
    // CREATE
    // ═══════════════════════════════════════════════════════════

    @Transactional
    public SipTrunk createTrunk(UUID tenantId, UUID subscriptionId, String name,
                                String provider, String sipServer, Integer sipPort,
                                TrunkTransport transport, TrunkAuthType authType,
                                String authUsername, String authPassword,
                                Integer maxConcurrent, String outboundCallerId,
                                String codecPreference) {

        if (trunkRepository.existsByTenantIdAndName(tenantId, name)) {
            throw new IllegalArgumentException("Trunk '" + name + "' already exists for tenant");
        }

        SipTrunk trunk = SipTrunk.builder()
                .tenantId(tenantId)
                .subscriptionId(subscriptionId)
                .name(name)
                .provider(provider)
                .sipServer(sipServer)
                .sipPort(sipPort != null ? sipPort : 5060)
                .transport(transport != null ? transport : TrunkTransport.UDP)
                .authType(authType != null ? authType : TrunkAuthType.DIGEST)
                .authUsername(authUsername)
                .authPasswordEncrypted(authPassword) // TODO: encrypt before storage
                .maxConcurrentOutbound(maxConcurrent != null ? maxConcurrent : 10)
                .outboundCallerId(outboundCallerId)
                .codecPreference(codecPreference)
                .build();

        trunk = trunkRepository.save(trunk);

        // Sync uacreg for digest auth trunks
        if (trunk.getAuthType() == TrunkAuthType.DIGEST || trunk.getAuthType() == TrunkAuthType.BOTH) {
            syncUacReg(trunk);
            kamailioReload.reloadUacReg();
        }

        log.info("Created trunk '{}' for tenant {} (provider={}, server={}:{})",
                name, tenantId, provider, sipServer, trunk.getSipPort());
        return trunk;
    }

    // ═══════════════════════════════════════════════════════════
    // UPDATE
    // ═══════════════════════════════════════════════════════════

    @Transactional
    public SipTrunk updateTrunk(UUID trunkId, String name, String sipServer, Integer sipPort,
                                TrunkTransport transport, String authUsername, String authPassword,
                                Integer maxConcurrent, String outboundCallerId,
                                String codecPreference, Boolean isActive) {

        SipTrunk trunk = trunkRepository.findById(trunkId)
                .orElseThrow(() -> new IllegalArgumentException("Trunk not found: " + trunkId));

        if (name != null) trunk.setName(name);
        if (sipServer != null) trunk.setSipServer(sipServer);
        if (sipPort != null) trunk.setSipPort(sipPort);
        if (transport != null) trunk.setTransport(transport);
        if (authUsername != null) trunk.setAuthUsername(authUsername);
        if (authPassword != null) trunk.setAuthPasswordEncrypted(authPassword); // TODO: encrypt
        if (maxConcurrent != null) trunk.setMaxConcurrentOutbound(maxConcurrent);
        if (outboundCallerId != null) trunk.setOutboundCallerId(outboundCallerId);
        if (codecPreference != null) trunk.setCodecPreference(codecPreference);
        if (isActive != null) trunk.setIsActive(isActive);

        trunk = trunkRepository.save(trunk);

        // Re-sync uacreg
        if (trunk.getAuthType() == TrunkAuthType.DIGEST || trunk.getAuthType() == TrunkAuthType.BOTH) {
            syncUacReg(trunk);
            kamailioReload.reloadUacReg();
        }

        log.info("Updated trunk {}", trunkId);
        return trunk;
    }

    // ═══════════════════════════════════════════════════════════
    // DELETE (soft-delete trunk, hard-delete uacreg)
    // ═══════════════════════════════════════════════════════════

    @Transactional
    public void deleteTrunk(UUID trunkId) {
        SipTrunk trunk = trunkRepository.findById(trunkId)
                .orElseThrow(() -> new IllegalArgumentException("Trunk not found: " + trunkId));

        // Soft-delete trunk
        trunk.setIsActive(false);
        trunkRepository.save(trunk);

        // Hard-delete uacreg (Kamailio should stop registering)
        uacRegRepository.deleteByTrunkId(trunkId);
        kamailioReload.reloadUacReg();

        log.info("Deleted trunk {} — uacreg removed, uac.reg_reload triggered", trunkId);
    }

    // ═══════════════════════════════════════════════════════════
    // QUERIES
    // ═══════════════════════════════════════════════════════════

    public List<SipTrunk> getByTenantId(UUID tenantId) {
        return trunkRepository.findByTenantIdAndIsActiveTrue(tenantId);
    }

    public Optional<SipTrunk> getById(UUID trunkId) {
        return trunkRepository.findById(trunkId);
    }

    // ═══════════════════════════════════════════════════════════
    // INTERNAL — uacreg sync
    // ═══════════════════════════════════════════════════════════

    /**
     * Create or update the Kamailio uacreg row for a trunk.
     *
     * l_uuid format: "trunk_{trunkId}" — unique key for kamcmd uac.reg_reload.
     * auth_proxy format: "sip:{sipServer}:{sipPort}" — where Kamailio sends REGISTER.
     */
    private void syncUacReg(SipTrunk trunk) {
        String lUuid = "trunk_" + trunk.getId();

        UacReg reg = uacRegRepository.findByLUuid(lUuid)
                .orElse(UacReg.builder()
                        .lUuid(lUuid)
                        .tenantId(trunk.getTenantId())
                        .trunkId(trunk.getId())
                        .build());

        reg.setLUsername(trunk.getAuthUsername() != null ? trunk.getAuthUsername() : "");
        reg.setLDomain(trunk.getSipServer());
        reg.setRUsername(trunk.getAuthUsername() != null ? trunk.getAuthUsername() : "");
        reg.setRDomain(trunk.getSipServer());
        reg.setRealm(trunk.getSipServer());
        reg.setAuthUsername(trunk.getAuthUsername() != null ? trunk.getAuthUsername() : "");
        reg.setAuthPassword(trunk.getAuthPasswordEncrypted() != null ? trunk.getAuthPasswordEncrypted() : "");
        reg.setAuthProxy("sip:" + trunk.getSipServer() + ":" + trunk.getSipPort());
        reg.setExpires(3600);
        reg.setFlags(0);
        reg.setRegDelay(0);

        uacRegRepository.save(reg);
        log.debug("Synced uacreg for trunk {} (l_uuid={})", trunk.getId(), lUuid);
    }
}