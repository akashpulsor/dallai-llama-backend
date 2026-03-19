package com.dalai.llama.pbx.core.controller.write;


import com.dalai.llama.pbx.core.domain.entity.kamailio.*;
import com.dalai.llama.pbx.core.repository.kamailio.*;
import com.dalai.llama.pbx.core.service.kamailio.KamailioReloadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Write APIs for Kamailio tables — called by tenant-service during provisioning.
 * Secured by Istio mTLS (no JWT). PBX-Core is the data owner; tenant-service
 * is the orchestrator that decides WHAT to write and in what order.
 *
 * Typical provisioning sequence from tenant-service:
 *   1. POST /subscribers  → create SIP auth entries
 *   2. POST /domains      → register tenant SIP domain
 *   3. POST /dispatchers  → set FreeSWITCH destination
 *   4. POST /dialplan     → number translation rules
 *   5. POST /uacreg       → trunk registrations
 *   6. POST /reload       → kamcmd reload all modules
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/write/kamailio")
@RequiredArgsConstructor
public class KamailioWriteController {

    private final SubscriberRepository subscriberRepository;
    private final SipDomainRepository domainRepository;
    private final DomainAttrsRepository domainAttrsRepository;
    private final DispatcherRepository dispatcherRepository;
    private final DialplanRepository dialplanRepository;
    private final UacRegRepository uacRegRepository;
    private final KamailioReloadService kamailioReload;

    // ═══════════════════════════════════════════════════════════
    // SUBSCRIBERS
    // ═══════════════════════════════════════════════════════════

    @PostMapping("/subscribers")
    @Transactional
    public ResponseEntity<List<Subscriber>> writeSubscribers(@RequestBody List<Subscriber> subscribers) {
        List<Subscriber> saved = subscriberRepository.saveAll(subscribers);
        log.info("Written {} subscribers", saved.size());
        return ResponseEntity.ok(saved);
    }

    @PostMapping("/subscribers/single")
    @Transactional
    public ResponseEntity<Subscriber> writeSubscriber(@RequestBody Subscriber subscriber) {
        Subscriber saved = subscriberRepository.save(subscriber);
        log.info("Written subscriber {}@{}", saved.getUsername(), saved.getDomain());
        return ResponseEntity.ok(saved);
    }

    // ═══════════════════════════════════════════════════════════
    // DOMAINS
    // ═══════════════════════════════════════════════════════════

    @PostMapping("/domains")
    @Transactional
    public ResponseEntity<SipDomain> writeDomain(@RequestBody SipDomain domain) {
        SipDomain saved = domainRepository.save(domain);
        log.info("Written domain {}", saved.getDomain());
        return ResponseEntity.ok(saved);
    }

    @PostMapping("/domain-attrs")
    @Transactional
    public ResponseEntity<List<DomainAttrs>> writeDomainAttrs(@RequestBody List<DomainAttrs> attrs) {
        List<DomainAttrs> saved = domainAttrsRepository.saveAll(attrs);
        log.info("Written {} domain attrs", saved.size());
        return ResponseEntity.ok(saved);
    }

    // ═══════════════════════════════════════════════════════════
    // DISPATCHERS
    // ═══════════════════════════════════════════════════════════

    @PostMapping("/dispatchers")
    @Transactional
    public ResponseEntity<Dispatcher> writeDispatcher(@RequestBody Dispatcher dispatcher) {
        Dispatcher saved = dispatcherRepository.save(dispatcher);
        log.info("Written dispatcher setid={} dest={}", saved.getSetid(), saved.getDestination());
        return ResponseEntity.ok(saved);
    }

    // ═══════════════════════════════════════════════════════════
    // DIALPLAN
    // ═══════════════════════════════════════════════════════════

    @PostMapping("/dialplan")
    @Transactional
    public ResponseEntity<List<Dialplan>> writeDialplan(@RequestBody List<Dialplan> rules) {
        List<Dialplan> saved = dialplanRepository.saveAll(rules);
        log.info("Written {} dialplan rules", saved.size());
        return ResponseEntity.ok(saved);
    }

    // ═══════════════════════════════════════════════════════════
    // UACREG (trunk registrations)
    // ═══════════════════════════════════════════════════════════

    @PostMapping("/uacreg")
    @Transactional
    public ResponseEntity<UacReg> writeUacReg(@RequestBody UacReg uacReg) {
        UacReg saved = uacRegRepository.save(uacReg);
        log.info("Written uacreg l_uuid={}", saved.getLUuid());
        return ResponseEntity.ok(saved);
    }

    // ═══════════════════════════════════════════════════════════
    // RELOAD — trigger kamcmd after all writes complete
    // ═══════════════════════════════════════════════════════════

    @PostMapping("/reload")
    public ResponseEntity<Map<String, String>> reload(
            @RequestParam(defaultValue = "all") String module) {
        switch (module.toLowerCase()) {
            case "domain" -> kamailioReload.reloadDomains();
            case "dispatcher" -> kamailioReload.reloadDispatchers();
            case "uacreg", "uac" -> kamailioReload.reloadUacReg();
            case "dialplan" -> kamailioReload.reloadDialplan();
            default -> kamailioReload.reloadAll();
        }
        log.info("Kamailio reload triggered: {}", module);
        return ResponseEntity.ok(Map.of("status", "reloaded", "module", module));
    }

    // ═══════════════════════════════════════════════════════════
    // TENANT CLEANUP (deprovision)
    // ═══════════════════════════════════════════════════════════

    @DeleteMapping("/tenant/{tenantId}")
    @Transactional
    public ResponseEntity<Map<String, String>> deleteTenant(@PathVariable UUID tenantId) {
        subscriberRepository.deleteByTenantId(tenantId);
        domainRepository.deleteByTenantId(tenantId);
        dispatcherRepository.deleteByTenantId(tenantId);
        dialplanRepository.deleteByTenantId(tenantId);
        uacRegRepository.deleteByTenantId(tenantId);
        kamailioReload.reloadAll();

        log.info("Deleted all Kamailio rows for tenant {} + reloaded", tenantId);
        return ResponseEntity.ok(Map.of("status", "deleted", "tenantId", tenantId.toString()));
    }

    /**
     * Suspend tenant — deactivate all subscribers + domain.
     * Existing calls continue; new REGISTER/INVITE rejected by Kamailio.
     */
    @PutMapping("/tenant/{tenantId}/suspend")
    @Transactional
    public ResponseEntity<Void> suspendTenant(@PathVariable UUID tenantId) {
        subscriberRepository.updateActiveByTenantId(tenantId, false);
        domainRepository.updateActiveByTenantId(tenantId, false);
        kamailioReload.reloadDomains();
        log.info("Suspended Kamailio entries for tenant {}", tenantId);
        return ResponseEntity.ok().build();
    }

    @PutMapping("/tenant/{tenantId}/resume")
    @Transactional
    public ResponseEntity<Void> resumeTenant(@PathVariable UUID tenantId) {
        subscriberRepository.updateActiveByTenantId(tenantId, true);
        domainRepository.updateActiveByTenantId(tenantId, true);
        kamailioReload.reloadDomains();
        log.info("Resumed Kamailio entries for tenant {}", tenantId);
        return ResponseEntity.ok().build();
    }
}