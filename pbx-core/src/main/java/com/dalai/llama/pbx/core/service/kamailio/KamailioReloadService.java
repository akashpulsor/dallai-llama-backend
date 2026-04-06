package com.dalai.llama.pbx.core.service.kamailio;


import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;

/**
 * Triggers Kamailio module reloads via JSONRPC over HTTP.
 *
 * Kamailio exposes a JSONRPC endpoint (configured in kamailio.cfg via jsonrpcs module).
 * PBX-Core calls it after writing to Kamailio tables so Kamailio picks up changes:
 *
 *   domain.reload     — after inserting/updating domain table rows
 *   dispatcher.reload — after inserting/updating dispatcher table rows
 *   uac.reg_reload   — after inserting/updating uacreg table rows
 *   dialplan.reload   — after inserting/updating dialplan table rows
 *
 * Called by:
 *   - KamailioWriteController after tenant-service writes rows
 *   - TrunkService after creating/updating trunks (uac.reg_reload)
 *
 * Kamailio runs on bare-metal (hostNetwork) — the JSONRPC URL is configured
 * in application.yml as kamailio.jsonrpc.url (default: http://127.0.0.1:5060/jsonrpc).
 */
@Slf4j
@Service
public class KamailioReloadService {

    private final WebClient kamailioClient;
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    public KamailioReloadService( WebClient kamailioClient) {
        this.kamailioClient = kamailioClient;
    }

    public void reloadDomains() {
        executeRpc("domain.reload", "domain");
    }

    public void reloadDispatchers() {
        executeRpc("dispatcher.reload", "dispatcher");
    }

    public void reloadUacReg() {
        executeRpc("uac.reg_reload", "uacreg");
    }

    public void reloadDialplan() {
        executeRpc("dialplan.reload", "dialplan");
    }

    /**
     * Reload all modules — called after full tenant provisioning.
     */
    public void reloadAll() {
        reloadDomains();
        reloadDispatchers();
        reloadDialplan();
        reloadUacReg();
        log.info("All Kamailio modules reloaded");
    }

    /**
     * Execute a Kamailio JSONRPC method call.
     *
     * Request format:
     *   {"jsonrpc": "2.0", "method": "domain.reload", "id": 1}
     *
     * Success response:
     *   {"jsonrpc": "2.0", "result": "OK", "id": 1}
     */
    private void executeRpc(String method, String moduleName) {
        try {
            Map<String, Object> request = Map.of(
                    "jsonrpc", "2.0",
                    "method", method,
                    "id", 1
            );

            String response = kamailioClient.post()
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(TIMEOUT)
                    .block();

            log.info("Kamailio {} → {}", method, response);
        } catch (Exception e) {
            // Log but don't throw — Kamailio might be temporarily unreachable
            // during startup or restart. The tables are already written;
            // Kamailio will pick them up on its next internal reload cycle.
            log.error("Kamailio {} failed: {} — tables written, reload will happen on next cycle",
                    method, e.getMessage());
        }
    }
}