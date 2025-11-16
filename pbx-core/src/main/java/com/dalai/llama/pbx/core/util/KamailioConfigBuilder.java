package com.dalai.llama.pbx.core.util;

import com.dalai.llama.pbx.core.model.SignalingConfig;
import com.dalai.llama.pbx.core.model.Tenant;
import org.springframework.stereotype.Component;

/**
 * Generates a complete kamailio.cfg fragment per-tenant, using Kafka for asynchronous
 * processing of registration events (location updates).
 *
 * Notes:
 * - Orchestrator MUST define: TENANT_REALM, RTPENGINE_SOCK, PBX_CORE_URL, KAFKA_BROKERS.
 */
@Component
public class KamailioConfigBuilder {

    public String build(Tenant tenant, SignalingConfig cfg) {
        // --- Input Configuration Retrieval ---
        String realm = cfg.getAuthRealm() == null ? "example.com" : cfg.getAuthRealm();
        String tenantId = tenant.getId();

        // 1. Determine topic names based on deployment model
        String deploymentModel = tenant.getDeploymentModel();
        String topicPrefix;

        if ("dedicated".equalsIgnoreCase(deploymentModel)) {
            // Use the tenant's realm, sanitized for Kafka topic naming conventions
            // (e.g., 'acme.com' -> 'acme_com')
            topicPrefix = tenant.getRealm().toLowerCase().replaceAll("[^a-z0-9]", "_");
        } else {
            // Default prefix for shared infrastructure
            topicPrefix = "pbx";
        }

        String regTopic = topicPrefix + "-registration-events";
        String callTopic = topicPrefix + "-call-events";

        // Extract WSS listener string
        String wssListener;
        if (cfg.getWssUrl() != null && cfg.getWssUrl().startsWith("wss://")) {
            String hostAndPort = cfg.getWssUrl().substring("wss://".length());
            if (hostAndPort.contains("/")) {
                hostAndPort = hostAndPort.substring(0, hostAndPort.indexOf("/"));
            }
            wssListener = "wss:" + hostAndPort;
        } else {
            wssListener = "wss:0.0.0.0:8089";
        }

        StringBuilder sb = new StringBuilder();

        // Header + recommended defines (orchestrator will already prefix some)
        sb.append("# Kamailio config fragment for tenant: ").append(tenantId).append("\\n");
        sb.append("# Realm: ").append(realm).append("\\n\\n");

        // --- REQUIRED DEFINES (Orchestrator must provide KAFKA_BROKERS) ---
        // Dynamically defined Kafka topics based on tenant deployment model
        sb.append("#!define KAFKA_REG_TOPIC \"").append(regTopic).append("\"\\n");
        sb.append("#!define KAFKA_CALL_TOPIC \"").append(callTopic).append("\"\\n");
        sb.append("# orchestrator should define: KAFKA_BROKERS\\n");
        sb.append("\\n");

        // --- MODULES ---
        sb.append("#### Loaded modules\\n");
        sb.append("loadmodule \"sl.so\"\\n");
        sb.append("loadmodule \"tm.so\"\\n");
        sb.append("loadmodule \"rr.so\"\\n");
        sb.append("loadmodule \"pv.so\"\\n");
        sb.append("loadmodule \"textops.so\"\\n");
        sb.append("loadmodule \"maxfwd.so\"\\n");
        sb.append("loadmodule \"uac.so\"\\n");
        sb.append("loadmodule \"dispatcher.so\"\\n");
        sb.append("loadmodule \"usrloc.so\"\\n");
        sb.append("loadmodule \"registrar.so\"\\n");
        sb.append("loadmodule \"dialog.so\"\\n");
        sb.append("loadmodule \"nathelper.so\"\\n");
        sb.append("loadmodule \"rtpengine.so\"\\n");
        sb.append("loadmodule \"http_client.so\"\\n");
        sb.append("loadmodule \"http_async_client.so\"\\n");
        sb.append("loadmodule \"websocket.so\"\\n");
        sb.append("loadmodule \"kafka.so\"\\n");
        sb.append("\\n");

        // --- MODULE PARAMETERS ---
        sb.append("# RTPengine socket\\n");
        sb.append("modparam(\"rtpengine\", \"rtpengine_sock\", RTPENGINE_SOCK)\\n");
        sb.append("modparam(\"dispatcher\", \"list_file\", \"/etc/kamailio/dispatcher.list\")\\n");
        sb.append("# Set the path for WebSocket connections\\n");
        sb.append("modparam(\"websocket\", \"ws_path\", \"/ws\")\\n");
        sb.append("# Kafka Broker list (provided by orchestrator)\\n");
        sb.append("modparam(\"kafka\", \"brokers\", KAFKA_BROKERS)\\n");
        sb.append("\\n");

        // --- GLOBALS ---
        sb.append("# WebSocket listener for WebRTC clients (WSS).\\n");
        sb.append("listen=").append(wssListener).append("\\n");
        sb.append("\\n");

        // --- REQUEST ROUTE ---
        sb.append("#### Request route (main)\\n");
        sb.append("request_route {\\n");

        // **FIX**: Initialize webrtc flag
        sb.append("    # Initialize webrtc flag for the ingress DTO. Using Kamailio 'false' string.\\n");
        sb.append("    $var(is_webrtc) = \"false\";\\n");

        // --- WebRTC/WSS Pre-routing and NAT handling ---
        sb.append("    # 1. Check for WebSocket connection and apply NAT/WebRTC flags\\n");
        sb.append("    if (is_ws()) {\\n");
        // **FIX**: Set webrtc flag if WSS
        sb.append("        $var(is_webrtc) = \"true\";\\n");
        sb.append("        xlog(\"L_INFO\", \"WebRTC/WSS Request detected: $rm from $fu\\n\");\\n");
        sb.append("        setflag(FLT_NATS);\\n");
        sb.append("        force_rport();\\n");
        sb.append("        if (is_method(\"REGISTER\")) {\\n");
        sb.append("            fix_nated_register();\\n");
        sb.append("            set_contact_alias();\\n");
        sb.append("        }\\n");
        sb.append("        if (is_method(\"INVITE\")) {\\n");
        sb.append("            rtpengine_manage(\"web_rtc\");\\n");
        sb.append("        }\\n");
        sb.append("    } else {\\n");
        sb.append("        # Standard SIP via UDP/TCP: apply NAT flags if contact indicates NAT\\n");
        sb.append("        if (nat_uac_test(6)) {\\n");
        sb.append("            force_rport();\\n");
        sb.append("            if (is_method(\"REGISTER\")) {\\n");
        sb.append("                fix_nated_register();\\n");
        sb.append("            }\\n");
        sb.append("            setflag(FLT_NATS);\\n");
        sb.append("        }\\n");
        sb.append("    }\\n");

        sb.append("    # basic protections\\n");
        sb.append("    if (!mf_process_maxfwd(10)) { sl_send_reply(483, \"Too Many Hops\"); exit; }\\n");
        sb.append("    if (is_method(\"OPTIONS\")) { sl_send_reply(200, \"OK\"); exit; }\\n");
        sb.append("\\n");

        sb.append("    # REGISTER handling - save location and exit. The event_route handles Kafka publishing.\\n");
        sb.append("    if (is_method(\"REGISTER\")) {\\n");
        sb.append("        save(\"location\");\\n");
        sb.append("        exit;\\n");
        sb.append("    }\\n");
        sb.append("\\n");

        // --- INVITE path: Call PBX Core ---
        sb.append("    if (is_method(\"INVITE\")) {\\n");
        sb.append("        append_hf(\"X-Realm: \" TENANT_REALM \"\\r\\n\");\\n");
        sb.append("        if (defined(AI_FEATURES) && AI_FEATURES!=\"none\") {\\n");
        sb.append("            append_hf(\"X-AI-Features: \" AI_FEATURES \"\\r\\n\");\\n");
        sb.append("        }\\n");
        sb.append("        rtpengine_offer();\\n");

        // **FIX**: Constructing the full JSON payload matching IngressCallRequest DTO
        sb.append(
                "        $var(request_body) = \"{"
                        + "\\\"callId\\\":\\\"\" + $ci + \"\\\""
                        + ",\\\"from\\\":\\\"\" + $fU + \"\\\""
                        + ",\\\"to\\\":\\\"\" + $rU + \"\\\""
                        + ",\\\"tenantId\\\":\\\"\" TENANT_REALM \"\\\""
                        + ",\\\"entrypoint\\\":\\\"\" + $rU + \"\\\""
                        + ",\\\"webrtc\\\":\" + $var(is_webrtc) + \""
                        + "}\";\\n"
        );

        sb.append("        http_async_query(PBX_CORE_URL \"/v1/calls/ingress\", $var(request_body), \"PBX_REPLY\");\\n");
        sb.append("        exit;\\n");
        sb.append("    }\\n");
        sb.append("}\\n\\n");

        // --- REGISTRAR EVENT ROUTE (Registration updates) ---
        sb.append("#### Registration Event Route: Send contact to Kafka\\n");
        sb.append("event_route[registrar:save] {\\n");
        sb.append("    # Iterate over all contacts for the user\\n");
        sb.append("    if (ul_on_contact(\"$rU\")) {\\n");
        sb.append("        while (ul_next_contact()) {\\n");
        sb.append("            $var(contact_uri) = $ct;\\n");
        sb.append("            $var(expires) = $ul(expires);\\n");
        sb.append("            $var(event_type) = \"REGISTER\";\\n");
        sb.append("            if ($ul(state) == 0) {\\n");
        sb.append("                $var(event_type) = \"UNREGISTER\";\\n");
        sb.append("            }\\n");
        sb.append("            \\n");
        sb.append("            # Payload MUST include tenantId (TENANT_REALM) and full contact URI\\n");
        sb.append("            $var(payload) = \"{\\\"tenantId\\\":\\\"\" TENANT_REALM \"\\\", \\\"aor\\\":\\\"\" $rU \"\\\", \\\"contact\\\":\\\"\" $var(contact_uri) \"\\\", \\\"expires\\\":\" $var(expires) \", \\\"eventType\\\":\\\"\" $var(event_type) \"\\\"}\";\\n");
        sb.append("            \\n");
        // Uses the KAFKA_REG_TOPIC defined above (which is either shared or dedicated)
        sb.append("            kafka_publish(KAFKA_REG_TOPIC, $rU, $var(payload));\\n");
        sb.append("            xlog(\"L_INFO\", \"Kafka: Published $var(event_type) for $rU to topic KAFKA_REG_TOPIC\\n\");\\n");
        sb.append("        }\\n");
        sb.append("    }\\n");
        sb.append("}\\n\\n");

        // --- PBX async reply handler (RTPengine Answer logic) ---
        sb.append("#### PBX async reply handler (http_async_client)\\n");
        sb.append("route[PBX_REPLY] {\\n");
        sb.append("    if ($http_reply_body == \"\") {\\n");
        sb.append("        xlog(\"L_WARN\", \"PBX replied empty body for call $ci\\n\");\\n");
        sb.append("        sl_send_reply(480, \"Temporarily Unavailable\");\\n");
        sb.append("        exit;\\n");
        sb.append("    }\\n");
        sb.append("    $var(body) = $http_reply_body;\\n");
        sb.append("    if ($var(body) =~ \"\\\\\\\"action\\\\\\\":\\\\\\\"reject\\\\\\\"\") {\\n");
        sb.append("        sl_send_reply(603, \"Rejected by PBX\");\\n");
        sb.append("        exit;\\n");
        sb.append("    }\\n");
        sb.append("    # Extract target if provided\\n");
        sb.append("    if ($var(body) =~ \"\\\\\\\"target\\\\\\\":\\\\\\\"([^\\\\\\\"]+)\\\\\\\"\") {\\n");
        sb.append("        $var(target) = $(re.pl(capture,1,$var(body)));\\n");
        sb.append("    } else {\\n");
        sb.append("        # fallback to dispatcher\\n");
        sb.append("        ds_select_dst(\"1\", \"4\");\\n");
        sb.append("        $var(target) = $du;\\n");
        sb.append("    }\\n");
        sb.append("    rtpengine_answer();\\n");
        sb.append("    # set destination URI and relay\\n");
        sb.append("    if ($var(target) != \"\") {\\n");
        sb.append("        route(relay_to_target);\\n");
        sb.append("    } else {\\n");
        sb.append("        sl_send_reply(480, \"No Target\");\\n");
        sb.append("    }\\n");
        sb.append("    exit;\\n");
        sb.append("}\\n\\n");

        // --- Relay route ---
        sb.append("route[relay_to_target] {\\n");
        sb.append("    if (is_method(\"INVITE\")) {\\n");
        sb.append("        if (lookup(\"location\")) {\\n");
        sb.append("            if ($du =~ \"^sip:[^@]+@[^;]*;transport=ws[s]?\") {\\n");
        sb.append("                 rtpengine_manage(\"web_rtc\");\\n");
        sb.append("            }\\n");
        sb.append("        }\\n");
        sb.append("    }\\n");
        sb.append("    if ($var(target) =~ \"^sip:\") {\\n");
        sb.append("        $du = $var(target);\\n");
        sb.append("    }\\n");
        sb.append("    if (!t_relay()) { sl_send_reply(500, \"Relay Failed\"); }\\n");
        sb.append("    exit;\\n");
        sb.append("}\\n\\n");

        // --- Dialog (call start / end) handlers - Now using Kafka ---
        sb.append("event_route[dialog:start] {\\n");
        sb.append("    # Publish dialog start event to Kafka\\n");
        sb.append("    $var(payload) = \"{\\\"tenantId\\\":\\\"\" TENANT_REALM \"\\\", \\\"callId\\\":\\\"\" $ci \"\\\", \\\"from\\\":\\\"\" $fU \"\\\", \\\"to\\\":\\\"\" $rU \"\\\", \\\"eventType\\\":\\\"dialog.start\\\"}\";\\n");
        // Uses the KAFKA_CALL_TOPIC defined above (which is either shared or dedicated)
        sb.append("    kafka_publish(KAFKA_CALL_TOPIC, $ci, $var(payload));\\n");
        sb.append("    xlog(\"L_INFO\",\"Kafka: Published dialog start for $ci -> $fU -> $rU\\n\");\\n");
        sb.append("}\\n\\n");

        sb.append("event_route[dialog:end] {\\n");
        sb.append("    # Publish dialog end event to Kafka\\n");
        sb.append("    $var(payload) = \"{\\\"tenantId\\\":\\\"\" TENANT_REALM \"\\\", \\\"callId\\\":\\\"\" $ci \"\\\", \\\"eventType\\\":\\\"dialog.end\\\"}\";\\n");
        // Uses the KAFKA_CALL_TOPIC defined above (which is either shared or dedicated)
        sb.append("    kafka_publish(KAFKA_CALL_TOPIC, $ci, $var(payload));\\n");
        sb.append("    xlog(\"L_INFO\",\"Kafka: Published dialog end for $ci\\n\");\\n");
        sb.append("}\\n\\n");

        // --- failure and onreply routes ---
        sb.append("failure_route {\\n");
        sb.append("    xlog(\"L_WARN\",\"failure_route: status=$T_reply_code for call $ci\\n\");\\n");
        sb.append("}\\n\\n");

        sb.append("onreply_route {\\n");
        sb.append("    # used for recording sip responses or forking handling\\n");
        sb.append("}\\n\\n");

        // Wrap up
        sb.append("#### End of generated config for tenant ").append(tenantId).append("\\n");

        return sb.toString();
    }
}