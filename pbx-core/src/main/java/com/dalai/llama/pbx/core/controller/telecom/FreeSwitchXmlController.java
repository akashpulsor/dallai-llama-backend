package com.dalai.llama.pbx.core.controller.telecom;


import com.dalai.llama.pbx.core.domain.entity.core.TenantDialplan;
import com.dalai.llama.pbx.core.domain.entity.kamailio.Subscriber;
import com.dalai.llama.pbx.core.repository.core.TenantDialplanRepository;
import com.dalai.llama.pbx.core.repository.kamailio.SubscriberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

/**
 * FreeSWITCH mod_xml_curl endpoints — called on EVERY call.
 *
 * FreeSWITCH is configured with:
 *   <param name="gateway-url" value="http://pbx-core:8080/internal/freeswitch/directory"/>
 *   <param name="gateway-url" value="http://pbx-core:8080/internal/freeswitch/dialplan"/>
 *
 * These replace static XML files — PBX-Core serves config dynamically from DB.
 *
 * Security: IP-restricted via TrustedIpAuthorizationManager (chain 1 in SecurityConfig).
 * FreeSWITCH runs on bare-metal outside the Kubernetes cluster.
 */
@Slf4j
@RestController
@RequestMapping("/freeswitch")
@RequiredArgsConstructor
public class FreeSwitchXmlController {

    private final SubscriberRepository subscriberRepository;
    private final TenantDialplanRepository dialplanRepository;

    /**
     * Directory lookup — FreeSWITCH asks "who is user X on domain Y?"
     * Returns subscriber credentials + tenant context variables.
     *
     * CRITICAL: user_context must be "tenant_{namespace}" (e.g., "tenant_acme")
     * to match the dialplan context stored in tenant_dialplan table.
     * The namespace comes from subscriber.namespace column (set during agent creation).
     */
    @GetMapping(value = "/directory", produces = MediaType.APPLICATION_XML_VALUE)
    public String directory(@RequestParam String domain,
                            @RequestParam String user,
                            @RequestParam(defaultValue = "sip_auth") String action) {

        log.debug("Directory: user={}@{} action={}", user, domain, action);

        Optional<Subscriber> subOpt = subscriberRepository.findByUsernameAndDomain(user, domain);

        if (subOpt.isEmpty() || Boolean.FALSE.equals(subOpt.get().getIsActive())) {
            log.warn("Directory: {}@{} not found or inactive", user, domain);
            return NOT_FOUND_XML;
        }

        Subscriber sub = subOpt.get();

        // Resolve namespace for user_context — must match dialplan context "tenant_{namespace}"
        String namespace = sub.getNamespace();
        if (namespace == null || namespace.isBlank()) {
            // Fallback: derive from domain "tenant-acme.dalaillama.in" → "acme"
            namespace = deriveNamespace(domain);
        }

        return """
                <?xml version="1.0" encoding="UTF-8" standalone="no"?>
                <document type="freeswitch/xml">
                  <section name="directory">
                    <domain name="%s">
                      <params>
                        <param name="dial-string" value="{^^:sip_invite_domain=${dialed_domain}:presence_id=${dialed_user}@${dialed_domain}}${sofia_contact(*/${dialed_user}@${dialed_domain})}"/>
                      </params>
                      <user id="%s">
                        <params>
                          <param name="a1-hash" value="%s"/>
                        </params>
                        <variables>
                          <variable name="tenant_id" value="%s"/>
                          <variable name="subscription_id" value="%s"/>
                          <variable name="namespace" value="%s"/>
                          <variable name="subscriber_type" value="%s"/>
                          <variable name="display_name" value="%s"/>
                          <variable name="user_context" value="tenant_%s"/>
                          <variable name="effective_caller_id_name" value="%s"/>
                          <variable name="effective_caller_id_number" value="%s"/>
                          <variable name="accountcode" value="%s"/>
                        </variables>
                      </user>
                    </domain>
                  </section>
                </document>""".formatted(
                domain,
                sub.getUsername(),
                sub.getHa1(),
                sub.getTenantId(),
                sub.getSubscriptionId(),
                namespace,
                sub.getSubscriberType(),
                sub.getDisplayName() != null ? escapeXml(sub.getDisplayName()) : sub.getUsername(),
                namespace,  // user_context = tenant_{namespace} — matches dialplan context
                sub.getDisplayName() != null ? escapeXml(sub.getDisplayName()) : sub.getUsername(),
                sub.getUsername(),
                sub.getTenantId()
        );
    }

    /**
     * Dialplan lookup — returns pre-generated XML from tenant_dialplan table.
     * Context = "tenant_{namespace}" set by directory's user_context variable.
     */
    @GetMapping(value = "/dialplan", produces = MediaType.APPLICATION_XML_VALUE)
    public String dialplan(@RequestParam String context,
                           @RequestParam(name = "destination_number", required = false) String destNumber,
                           @RequestParam(name = "Caller-Caller-ID-Number", required = false) String callerIdNumber,
                           @RequestParam(name = "variable_tenant_id", required = false) String tenantId) {

        log.debug("Dialplan: context={} dest={} caller={} tenant={}", context, destNumber, callerIdNumber, tenantId);

        Optional<TenantDialplan> dpOpt = dialplanRepository.findByContext(context);

        if (dpOpt.isEmpty()) {
            log.warn("Dialplan: context '{}' not found — returning default hangup", context);
            return defaultDialplan(context, destNumber);
        }

        return dpOpt.get().getDialplanXml();
    }

    // ═══════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════

    /**
     * Derive namespace from SIP domain when subscriber.namespace is null.
     * "tenant-acme.dalaillama.in" → "acme"
     */
    private String deriveNamespace(String sipDomain) {
        if (sipDomain != null && sipDomain.contains(".")) {
            String firstPart = sipDomain.split("\\.")[0]; // "tenant-acme"
            if (firstPart.startsWith("tenant-")) {
                return firstPart.substring("tenant-".length()); // "acme"
            }
            return firstPart;
        }
        return sipDomain;
    }

    private static final String NOT_FOUND_XML = """
            <?xml version="1.0" encoding="UTF-8" standalone="no"?>
            <document type="freeswitch/xml">
              <section name="directory">
              </section>
            </document>""";

    private String defaultDialplan(String context, String destNumber) {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="no"?>
                <document type="freeswitch/xml">
                  <section name="dialplan" description="PBX-Core Dynamic Dialplan">
                    <context name="%s">
                      <extension name="no_dialplan">
                        <condition field="destination_number" expression="^(.*)$">
                          <action application="log" data="WARNING: No dialplan for context=%s dest=%s"/>
                          <action application="playback" data="ivr/ivr-invalid_extension.wav"/>
                          <action application="hangup" data="NO_ROUTE_DESTINATION"/>
                        </condition>
                      </extension>
                    </context>
                  </section>
                </document>""".formatted(context, context, destNumber != null ? destNumber : "unknown");
    }

    private String escapeXml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
