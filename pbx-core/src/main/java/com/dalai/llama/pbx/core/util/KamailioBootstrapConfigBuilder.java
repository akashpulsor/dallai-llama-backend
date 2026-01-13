package com.dalai.llama.pbx.core.util;

import com.dalai.llama.pbx.core.model.SignalingConfig;
import com.dalai.llama.pbx.core.model.Tenant;
import org.springframework.stereotype.Component;

/**
 * Minimal kamailio config for tenant bootstrap.
 * Keeps only register + INVITE → PBX core ingress + health.
 */
@Component
public class KamailioBootstrapConfigBuilder {

    public String buildBootstrap(Tenant tenant, SignalingConfig cfg) {
        String tenantId = tenant.getId();
        String realm = cfg.getAuthRealm() == null ? "example.com" : cfg.getAuthRealm();
        // pbx core internal URL or service FQDN
        String pbxCoreUrl = cfg.getPbxFqdn() != null ? cfg.getPbxFqdn() : "http://pbx-core.svc.cluster.local:8080";

        return String.format("""
#!define TENANT_ID "%s"
#!define TENANT_REALM "%s"
#!define PBX_CORE_URL "%s"

# Minimal Kamailio config - bootstrap
loadmodule "sl.so"
loadmodule "tm.so"
loadmodule "pv.so"
loadmodule "textops.so"
loadmodule "maxfwd.so"
loadmodule "usrloc.so"
loadmodule "registrar.so"
loadmodule "http_async_client.so"

listen=udp:0.0.0.0:5060
listen=tcp:0.0.0.0:5060

request_route {
    if (is_method("OPTIONS")) {
        sl_send_reply(200, "OK");
        exit;
    }

    # REGISTER: minimal challenge + forward to PBX auth endpoint (async)
    if (is_method("REGISTER")) {
        if ($hdr(Authorization) == "") {
            www_challenge(TENANT_REALM, "0");
            exit;
        }
        # naive save, PBX can verify via AUTH_REPLY route if implemented
        save("location");
        sl_send_reply(200, "OK");
        exit;
    }

    # INVITE: send to PBX core ingress for decision (DID lookup / IVR)
    if (is_method("INVITE")) {
        $var(request_body) = "{\\"callId\\":\\"$ci\\",\\"from\\":\\"$fu\\",\\"to\\":\\"$rU\\",\\"tenantId\\":\\"TENANT_ID\\"}";
        http_async_query(PBX_CORE_URL "/v1/calls/ingress", $var(request_body), "PBX_REPLY");
        exit;
    }

    # fallback
    sl_send_reply(404, "Not Found");
    exit;
}

route[PBX_REPLY] {
    if ($http_reply_code == 200 && $http_reply_body != "") {
        # PBX returned routing decision -- simple pass-through to the provided contact or branch
        # PBX may provide "contact" or "target" field; keep basic behaviour for bootstrap
        if ($http_reply_body =~ "\\\\""contact\\\\"" ) {
            # extract contact using regex capture is heavier; keep it simple in bootstrap
            t_relay();
            exit;
        }
        t_relay();
        exit;
    } else {
        sl_send_reply(480, "PBX Unavailable");
        exit;
    }
}
""", tenantId, realm, pbxCoreUrl);
    }
}
