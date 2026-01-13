package com.dalai.llama.pbx.core.util;

import com.dalai.llama.pbx.core.model.Did;
import com.dalai.llama.pbx.core.model.SignalingConfig;
import com.dalai.llama.pbx.core.model.Tenant;
import com.dalai.llama.pbx.core.model.Trunk;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Production-ready Kamailio config generator (per tenant) — JSON-RPC originate model.
 */
@Component
public class KamailioConfigBuilder {

    @Value("${pbx.core.url:localhost:8080}")
    private String pbxCoreUrl;

    public String build(Tenant tenant,
                        SignalingConfig cfg,
                        List<Trunk> trunks,
                        List<Did> dids) {

        String realm = cfg.getAuthRealm() == null ? "example.com" : cfg.getAuthRealm();
        String tenantId = tenant.getId();
        String agentAuthUrl = System.getenv("AGENT_AUTH_URL"); // e.g. http://agent-service

        String topicPrefix = "pbx";
        if ("dedicated".equalsIgnoreCase(tenant.getDeploymentModel()) && tenant.getRealm() != null) {
            topicPrefix = tenant.getRealm().toLowerCase().replaceAll("[^a-z0-9]", "_");
        }

        String regTopic = topicPrefix + "-registration-events";
        String callTopic = topicPrefix + "-call-events";

        String wssListener;
        if (cfg.getWssUrl() != null && cfg.getWssUrl().startsWith("wss://")) {
            String host = cfg.getWssUrl().substring(6);
            if (host.contains("/")) host = host.substring(0, host.indexOf("/"));
            wssListener = "wss:" + host;
        } else {
            wssListener = "wss:0.0.0.0:8089";
        }

        StringBuilder sb = new StringBuilder();
        if (agentAuthUrl == null || agentAuthUrl.isBlank()) {
            sb.append("# WARNING: AGENT_AUTH_URL not set in environment - registrations will fail authentication\n");
        }

        sb.append("# Kamailio config for tenant: ").append(tenantId).append("\n");
        sb.append("# Realm: ").append(realm).append("\n\n");

        sb.append("#!define KAFKA_REG_TOPIC \"").append(regTopic).append("\"\n");
        sb.append("#!define KAFKA_CALL_TOPIC \"").append(callTopic).append("\"\n");
        sb.append("#!define AGENT_AUTH_URL \"").append(agentAuthUrl == null ? "" : agentAuthUrl).append("\"\n");
        sb.append("#!define TENANT_ID \"").append(tenantId).append("\"\n");
        sb.append("#!define TENANT_REALM \"").append(realm).append("\"\n");
        sb.append("#!define RTPENGINE_SOCK \"").append(tenant.getRtpengineSock() == null ? "" : tenant.getRtpengineSock()).append("\"\n");
        sb.append("#!define KAFKA_BROKERS \"").append(tenant.getKafkaBootstrap() == null ? "" : tenant.getKafkaBootstrap()).append("\"\n");
        sb.append("#!define PBX_CORE_URL \"").append(pbxCoreUrl).append("\"\n");
        sb.append("#!define IVR_SIP_URI \"sip:ivr@pbx-core:5080\"\n\n");

        // modules
        sb.append("loadmodule \"sl.so\"\n");
        sb.append("loadmodule \"tm.so\"\n");
        sb.append("loadmodule \"rr.so\"\n");
        sb.append("loadmodule \"pv.so\"\n");
        sb.append("loadmodule \"textops.so\"\n");
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
        // JSON-RPC module for Option A originate
        sb.append("loadmodule \"jsonrpcs.so\"\n\n");

        // params
        sb.append("modparam(\"rtpengine\", \"rtpengine_sock\", RTPENGINE_SOCK)\n");
        sb.append("modparam(\"rtpengine\", \"rtpengine_disable_dtls\", 0)\n");
        sb.append("modparam(\"rtpengine\", \"rtpengine_allow_opus\", 1)\n");
        sb.append("modparam(\"dispatcher\", \"list_file\", \"/etc/kamailio/dispatcher.list\")\n");
        sb.append("modparam(\"kafka\", \"brokers\", KAFKA_BROKERS)\n");
        sb.append("modparam(\"websocket\", \"ws_path\", \"/ws\")\n");
        // jsonrpcs can be left default; you may add auth restrictions if required

        sb.append("\nlisten=").append(wssListener).append("\n\n");
        sb.append("listen=udp:0.0.0.0:5060\n");
        sb.append("listen=tcp:0.0.0.0:5060\n\n");

        // request_route (same as before)...
        sb.append("request_route {\n");
        sb.append("    $var(is_webrtc) = \"false\";\n\n");
        sb.append("    if (is_ws()) {\n");
        sb.append("        $var(is_webrtc) = \"true\";\n");
        sb.append("        setflag(FLT_NATS);\n");
        sb.append("        force_rport();\n");
        sb.append("        if (is_method(\"REGISTER\")) { fix_nated_register(); set_contact_alias(); }\n");
        sb.append("        if (is_method(\"INVITE\")) { rtpengine_manage(\"web_rtc\"); }\n");
        sb.append("    } else if (nat_uac_test(6)) {\n");
        sb.append("        force_rport(); setflag(FLT_NATS);\n");
        sb.append("    }\n\n");
        sb.append("    if (!mf_process_maxfwd(10)) { sl_send_reply(483, \"Too Many Hops\"); exit; }\n");
        sb.append("    if (is_method(\"OPTIONS\")) { sl_send_reply(200, \"OK\"); exit; }\n\n");
        sb.append("    if ($rd == \"health\" || $rU == \"health\") { sl_send_reply(200, \"OK\"); exit; }\n\n");

        // REGISTER auth async to tenant-aware agent endpoint
        sb.append("    if (is_method(\"REGISTER\")) {\n");
        sb.append("        if (is_ws()) { fix_nated_register(); set_contact_alias(); }\n");
        sb.append("        if ($hdr(Authorization) == \"\") { www_challenge(TENANT_REALM, \"0\"); exit; }\n");
        sb.append("        $var(auth_body) = \"{\"\n");
        sb.append("            + \"\\\"username\\\":\\\"\" + $au + \"\\\",\" \n");
        sb.append("            + \"\\\"realm\\\":\\\"\" TENANT_REALM \"\\\",\" \n");
        sb.append("            + \"\\\"tenantId\\\":\\\"\" + TENANT_ID + \"\\\",\" \n");
        sb.append("            + \"\\\"nonce\\\":\\\"\" + $an + \"\\\",\" \n");
        sb.append("            + \"\\\"uri\\\":\\\"\" + $tu + \"\\\",\" \n");
        sb.append("            + \"\\\"response\\\":\\\"\" + $ar + \"\\\",\" \n");
        sb.append("            + \"\\\"method\\\":\\\"\" + $rm + \"\\\"\"\n");
        sb.append("            + \"}\";\n");
        sb.append("        http_async_query(AGENT_AUTH_URL \"/api/v1/tenants/\" TENANT_ID \"/agents/sip/auth\", $var(auth_body), \"AUTH_REPLY\");\n");
        sb.append("        exit;\n");
        sb.append("    }\n\n");

        // INVITE -> ingress to PBX-Core (DID mapping + rtpengine setup)
        sb.append("    if (is_method(\"INVITE\")) {\n");
        sb.append("        append_hf(\"X-Realm: \" TENANT_REALM \"\\r\\n\");\n");
        sb.append("        if ($var(is_webrtc) == \"true\") { rtpengine_manage(\"webrtc replace-origin replace-session-connection\"); }\n");
        sb.append("        else { rtpengine_manage(\"replace-origin replace-session-connection\"); }\n");
        sb.append("        $var(ten_entrypoint) = \"\";\n");

        if (dids != null && !dids.isEmpty()) {
            sb.append("        # DID Routing (generated)\n");
            for (Did d : dids) {
                String number = d.getNumber();
                String entry = escape(d.getEntrypoint());
                if (number != null && !number.isBlank()) {
                    sb.append("        if ($rU == \"").append(number).append("\") $var(ten_entrypoint) = \"").append(entry).append("\";\n");
                }
            }
            sb.append("\n");
            sb.append("        if ($var(ten_entrypoint) != \"\") append_hf(\"X-Entrypoint: \" + $var(ten_entrypoint) + \"\\r\\n\");\n\n");
        }

        sb.append("        $var(request_body) = \"{\"\n");
        sb.append("            + \"\\\"callId\\\":\\\"\" + $ci + \"\\\",\" \n");
        sb.append("            + \"\\\"from\\\":\\\"\" + $fU + \"\\\",\" \n");
        sb.append("            + \"\\\"to\\\":\\\"\" + $rU + \"\\\",\" \n");
        sb.append("            + \"\\\"tenantId\\\":\\\"\" TENANT_ID \"\\\",\" \n");
        sb.append("            + \"\\\"entrypoint\\\":\\\"\" + ($var(ten_entrypoint)==\"\"?$rU:$var(ten_entrypoint)) + \"\\\",\" \n");
        sb.append("            + \"\\\"webrtc\\\":\" + $var(is_webrtc) \n");
        sb.append("            + \"}\";\n\n");

        sb.append("        http_async_query(PBX_CORE_URL \"/v1/calls/ingress\", $var(request_body), \"PBX_REPLY\");\n");
        sb.append("        exit;\n");
        sb.append("    }\n"); // end INVITE
        sb.append("}\n\n"); // end request_route

        // registrar save -> kafka
        sb.append("event_route[registrar:save] {\n");
        sb.append("    if (ul_on_contact(\"$rU\")) while (ul_next_contact()) {\n");
        sb.append("        $var(payload) = \"{\\\"tenantId\\\":\\\"\" TENANT_ID \"\\\",\\\"aor\\\":\\\"\" $rU \"\\\",\\\"contact\\\":\\\"\" $ct \"\\\",\\\"expires\\\":\" $ul(expires) \",\\\"eventType\\\":\\\"REGISTER\\\"}\";\n");
        sb.append("        kafka_publish(KAFKA_REG_TOPIC, $rU, $var(payload));\n");
        sb.append("    }\n");
        sb.append("}\n\n");

        // AUTH_REPLY
        sb.append("route[AUTH_REPLY] {\n");
        sb.append("    if ($http_reply_code == 200) { save(\"location\"); sl_send_reply(200, \"OK\"); exit; }\n");
        sb.append("    if ($http_reply_code == 401) { www_challenge(TENANT_REALM, \"0\"); exit; }\n");
        sb.append("    if ($http_reply_code == 403 || $http_reply_code == 404) { sl_send_reply(403, \"Forbidden\"); exit; }\n");
        sb.append("    sl_send_reply(500, \"Auth Server Error\"); exit;\n");
        sb.append("}\n\n");

        // PBX_REPLY: decision handler (deliver / ivr / fallback / reject)
        sb.append("route[PBX_REPLY] {\n");
        sb.append("    if ($http_reply_body == \"\") { sl_send_reply(480, \"Temporarily Unavailable\"); exit; }\n");
        sb.append("    $var(body) = $http_reply_body;\n\n");
        sb.append("    if ($var(body) =~ \"\\\\\\\"action\\\\\\\":\\\\\\\"reject\\\\\\\"\") { sl_send_reply(603, \"Rejected by PBX\"); exit; }\n");
        sb.append("    if ($var(body) =~ \"\\\\\\\"decision\\\\\\\":\\\\\\\"fallback\\\\\\\"\") { sl_send_reply(480, \"No Agent Available\"); exit; }\n");
        sb.append("    if ($var(body) =~ \"\\\\\\\"action\\\\\\\":\\\\\\\"ivr\\\\\\\"\") {\n");
        sb.append("        if ($var(body) =~ \"\\\\\\\"ivrFlow\\\\\\\":\\\\\\\"([^\\\\\\\"]+)\\\\\\\"\") { $var(ivf) = $(re.pl(capture,1,$var(body))); append_hf(\"X-IVR-Flow: \" + $var(ivf) + \"\\\\r\\\\n\"); }\n");
        sb.append("        if ($var(body) =~ \"\\\\\\\"contact\\\\\\\":\\\\\\\"([^\\\\\\\"]+)\\\\\\\"\") { $var(target) = $(re.pl(capture,1,$var(body))); }\n");
        sb.append("        else if (IVR_SIP_URI != \"\") { $var(target) = IVR_SIP_URI; } else { sl_send_reply(500, \"IVR target missing\"); exit; }\n");
        sb.append("        append_hf(\"X-Mode: ivr\\\\r\\\\n\"); rtpengine_manage(\"replace-origin replace-session-connection\"); route(relay_to_target); exit;\n");
        sb.append("    }\n\n");

        sb.append("    if ($var(body) =~ \"\\\\\\\"contact\\\\\\\":\\\\\\\"([^\\\\\\\"]+)\\\\\\\"\") { $var(target) = $(re.pl(capture,1,$var(body))); }\n");
        sb.append("    else if ($var(body) =~ \"\\\\\\\"target\\\\\\\":\\\\\\\"([^\\\\\\\"]+)\\\\\\\"\") { $var(target) = $(re.pl(capture,1,$var(body))); }\n");
        sb.append("    else { ds_select_dst(\"1\",\"4\"); $var(target) = $du; }\n\n");

        sb.append("    rtpengine_manage(\"replace-origin replace-session-connection\"); route(relay_to_target); exit;\n");
        sb.append("}\n\n");

        // relay_to_target (outbound logic unchanged)
        sb.append("route[relay_to_target] {\n");
        sb.append("    if ($var(target) == \"\") { if (IVR_SIP_URI != \"\") { $var(target) = IVR_SIP_URI; } else { sl_send_reply(500, \"Missing target\"); exit; } }\n");
        sb.append("    $var(pnum) = \"\";\n");
        sb.append("    if ($var(target) =~ \"^pstn:\") { $var(pnum) = $(subst{re.replace,^pstn:,, $var(target)}); }\n");
        sb.append("    else if ($var(target) =~ \"^[+0-9]+$\") { $var(pnum) = $var(target); }\n");
        sb.append("    if ($var(pnum) == \"\") { if ($var(target) =~ \"^sip:\") { $du = $var(target); } t_relay(); exit; }\n\n");

        // trunk selection generation
        sb.append("    $var(selected_trunk) = \"\";\n");
        sb.append("    $var(selected_user)  = \"\";\n");
        sb.append("    $var(selected_pass)  = \"\";\n\n");

        if (trunks != null && !trunks.isEmpty()) {
            for (Trunk t : trunks) {
                String host = extractHost(t.getSipUri());
                String prefixes = t.getPrefixes() == null ? "" : t.getPrefixes();
                String prefRegex = formatPrefixes(prefixes);
                sb.append("    # Trunk rule: ").append(t.getName()).append("\n");
                sb.append("    if ($var(selected_trunk)==\"\" && $var(pnum) =~ \"^(").append(prefRegex).append(")\") {\n");
                sb.append("        $var(selected_trunk) = \"").append(host).append("\";\n");
                sb.append("        $var(selected_user)  = \"").append(n(t.getUsername())).append("\";\n");
                sb.append("        $var(selected_pass)  = \"").append(n(t.getPassword())).append("\";\n");
                sb.append("    }\n");
            }
        }

        sb.append("\n    if ($var(selected_trunk)==\"\") {\n");
        sb.append("        $var(selected_trunk) = \"").append(fallbackHost(trunks)).append("\";\n");
        sb.append("        $var(selected_user)  = \"").append(n(fallbackUser(trunks))).append("\";\n");
        sb.append("        $var(selected_pass)  = \"").append(n(fallbackPass(trunks))).append("\";\n");
        sb.append("    }\n\n");

        sb.append("    $du = \"sip:\" + $var(pnum) + \"@\" + $var(selected_trunk) + \";transport=udp\";\n");
        sb.append("    if ($var(selected_user) != \"\") uac_auth();\n");
        sb.append("    t_relay(); exit;\n");
        sb.append("}\n\n");

        // dialog events -> kafka
        sb.append("event_route[dialog:start] {\n");
        sb.append("    $var(payload) = \"{\\\"tenantId\\\":\\\"\" TENANT_ID \"\\\",\\\"callId\\\":\\\"\" $ci \"\\\",\\\"eventType\\\":\\\"dialog.start\\\"}\";\n");
        sb.append("    kafka_publish(KAFKA_CALL_TOPIC, $ci, $var(payload));\n");
        sb.append("}\n\n");

        sb.append("event_route[dialog:end] {\n");
        sb.append("    $var(payload) = \"{\\\"tenantId\\\":\\\"\" TENANT_ID \"\\\",\\\"callId\\\":\\\"\" $ci \"\\\",\\\"eventType\\\":\\\"dialog.end\\\"}\";\n");
        sb.append("    kafka_publish(KAFKA_CALL_TOPIC, $ci, $var(payload));\n");
        sb.append("}\n\n");

        sb.append("failure_route { xlog(\"L_WARN\",\"failure_route: status=$T_reply_code for call $ci\\n\"); }\n");
        sb.append("onreply_route { }\n\n");

        sb.append("# End of generated config for tenant ").append(tenantId).append("\n");

        return sb.toString();
    }

    // small helpers (placed in the same class)
    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\"", "\\\"");
    }

    private static String extractHost(String sipUri) {
        if (sipUri == null) return "";
        String u = sipUri.startsWith("sip:") ? sipUri.substring(4) : sipUri;
        if (u.contains("@")) u = u.substring(u.indexOf('@') + 1);
        if (u.contains(";")) u = u.substring(0, u.indexOf(';'));
        if (u.contains("?")) u = u.substring(0, u.indexOf('?'));
        return u;
    }

    private static String formatPrefixes(String prefixes) {
        if (prefixes == null || prefixes.isBlank()) return ".*";
        return prefixes.replace(",", "|").replace(" ", "");
    }

    private static String n(String s) {
        return s == null ? "" : s;
    }

    private static String fallbackHost(List<Trunk> trunks) {
        return (trunks != null && !trunks.isEmpty()) ? extractHost(trunks.get(0).getSipUri()) : "";
    }
    private static String fallbackUser(List<Trunk> trunks) {
        return (trunks != null && !trunks.isEmpty()) ? n(trunks.get(0).getUsername()) : "";
    }
    private static String fallbackPass(List<Trunk> trunks) {
        return (trunks != null && !trunks.isEmpty()) ? n(trunks.get(0).getPassword()) : "";
    }
}
