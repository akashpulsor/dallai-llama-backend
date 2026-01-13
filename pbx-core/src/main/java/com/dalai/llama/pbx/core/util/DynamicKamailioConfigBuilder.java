package com.dalai.llama.pbx.core.util;

import com.dalai.llama.pbx.core.model.*;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Builds a full Kamailio config for tenant with dispatcher, DID rules and trunk selection.
 * Keeps the same structure as your earlier KamailioConfigBuilder but usable for dynamic updates.
 */
@Component
public class DynamicKamailioConfigBuilder {

    public String build(Tenant tenant,
                        SignalingConfig cfg,
                        List<Trunk> trunks,
                        List<Did> dids,
                        String pbxCoreUrl) {

        String realm = cfg.getAuthRealm() == null ? "example.com" : cfg.getAuthRealm();
        String tenantId = tenant.getId();
        String agentAuthUrl = System.getenv("AGENT_AUTH_URL") == null ? "" : System.getenv("AGENT_AUTH_URL");

        StringBuilder sb = new StringBuilder();
        sb.append("#!define TENANT_REALM \"").append(realm).append("\"\n");
        sb.append("#!define TENANT_ID \"").append(tenantId).append("\"\n");
        sb.append("#!define PBX_CORE_URL \"").append(pbxCoreUrl).append("\"\n");
        sb.append("#!define RTPENGINE_SOCK \"").append(tenant.getRtpengineSock() == null ? "" : tenant.getRtpengineSock()).append("\"\n");
        sb.append("#!define KAFKA_BROKERS \"").append(tenant.getKafkaBootstrap() == null ? "" : tenant.getKafkaBootstrap()).append("\"\n\n");

        // modules and params (same minimal used earlier but extended)
        sb.append("loadmodule \"sl.so\"\n");
        sb.append("loadmodule \"tm.so\"\n");
        sb.append("loadmodule \"rr.so\"\n");
        sb.append("loadmodule \"pv.so\"\n");
        sb.append("loadmodule \"maxfwd.so\"\n");
        sb.append("loadmodule \"uac.so\"\n");
        sb.append("loadmodule \"dispatcher.so\"\n");
        sb.append("loadmodule \"usrloc.so\"\n");
        sb.append("loadmodule \"registrar.so\"\n");
        sb.append("loadmodule \"dialog.so\"\n");
        sb.append("loadmodule \"nathelper.so\"\n");
        sb.append("loadmodule \"rtpengine.so\"\n");
        sb.append("loadmodule \"http_client.so\"\n");
        sb.append("loadmodule \"http_async_client.so\"\n");
        sb.append("loadmodule \"websocket.so\"\n");
        sb.append("loadmodule \"kafka.so\"\n");
        sb.append("\nmodparam(\"rtpengine\", \"rtpengine_sock\", RTPENGINE_SOCK)\n");
        sb.append("modparam(\"dispatcher\",\"list_file\", \"/etc/kamailio/dispatcher.list\")\n");
        sb.append("modparam(\"kafka\", \"brokers\", KAFKA_BROKERS)\n\n");

        sb.append("listen=udp:0.0.0.0:5060\n");
        sb.append("listen=tcp:0.0.0.0:5060\n\n");

        // register/auth + invite handling (simplified)
        sb.append("request_route {\n");
        sb.append("    if (is_method(\"OPTIONS\")) { sl_send_reply(200, \"OK\"); exit; }\n");
        sb.append("    if (is_method(\"REGISTER\")) { if ($hdr(Authorization)==\"\") { www_challenge(TENANT_REALM, \"0\"); exit; } save(\"location\"); sl_send_reply(200, \"OK\"); exit; }\n");
        sb.append("    if (is_method(\"INVITE\")) {\n");
        sb.append("        append_hf(\"X-Realm: \" TENANT_REALM \"\\r\\n\");\n");
        sb.append("        rtpengine_manage(\"replace-origin replace-session-connection\");\n");

        // DID routing - set X-Entrypoint header for PBX decision
        if (dids != null && !dids.isEmpty()) {
            sb.append("        # DID mapping\n");
            for (Did d : dids) {
                if (d.getNumber() == null || d.getEntrypoint() == null) continue;
                sb.append("        if ($rU == \"").append(d.getNumber()).append("\") { append_hf(\"X-Entrypoint: ").append(escape(d.getEntrypoint())).append("\\r\\n\"); }\n");
            }
        }

        sb.append("        $var(request) = \"{\\\"callId\\\":\\\"\" + $ci + \"\\\",\\\"from\\\":\\\"\" + $fu + \"\\\",\\\"to\\\":\\\"\" + $rU + \"\\\",\\\"tenantId\\\":\\\"\" TENANT_ID \"\\\"}\";\n");
        sb.append("        http_async_query(PBX_CORE_URL \"/v1/calls/ingress\", $var(request), \"PBX_REPLY\");\n");
        sb.append("        exit;\n");
        sb.append("    }\n");
        sb.append("}\n\n");

        // PBX reply handler (basic)
        sb.append("route[PBX_REPLY] {\n");
        sb.append("    if ($http_reply_code == 200 && $http_reply_body != \"\") {\n");
        sb.append("        # PBX should have returned a 'contact' or 'target' — we just relay\n");
        sb.append("        if ($http_reply_body =~ \"\\\\\\\"contact\\\\\\\":\\\\\\\"\") { t_relay(); exit; }\n");
        sb.append("        t_relay(); exit;\n");
        sb.append("    } else { sl_send_reply(480, \"Temporarily Unavailable\"); exit; }\n");
        sb.append("}\n\n");

        // dispatcher uses dispatcher.list mounted from configmap
        sb.append("# end of generated kamailio config for tenant ").append(tenantId).append("\n");

        return sb.toString();
    }

    public String buildForIVR(String tenantId, List<IvrNode> nodes) {

        StringBuilder sb = new StringBuilder();

        sb.append("\n\n# ===============================\n");
        sb.append("# IVR Injection for Tenant ").append(tenantId).append("\n");
        sb.append("# ===============================\n\n");

        // Map IVR node IDs → SIP target
        for (IvrNode node : nodes) {
            String id = node.getNodeId();
            String fsTarget = "sip:ivr_%s_%s@freeswitch-".formatted(id, tenantId) + tenantId + ":5080";

            sb.append("""
                if ($rU == "%s") {
                    $du = "%s";
                    route(relay_to_target);
                    exit;
                }
                
            """.formatted(id, fsTarget));
        }

        // Entry from PBX response (X-IVR-Flow)
        sb.append("""
            if ($hdr(X-IVR-Flow) != "") {
                $var(ivrn) = $hdr(X-IVR-Flow);
                $du = "sip:" + $var(ivrn) + "@freeswitch-%s:5080";
                route(relay_to_target);
                exit;
            }
        """.formatted(tenantId));

        return sb.toString();
    }
    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\"", "\\\"");
    }
}
