package com.dalai.llama.pbx.core.util;

import org.springframework.stereotype.Component;

/**
 * Minimal FreeSWITCH dialplan to bootstrap a tenant.
 * Placeholder dialplan - answers and plays silence, then hangs up.
 */
@Component
public class FreeSwitchBootstrapBuilder {

    public String buildBootstrapFsDialplan(String tenantId) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<include>\n" +
                "  <extension name=\"bootstrap-" + tenantId + "\">\n" +
                "    <condition field=\"destination_number\" expression=\"(.*)\">\n" +
                "      <action application=\"log\" data=\"INFO Tenant " + tenantId + " inbound placeholder\"/>\n" +
                "      <!-- minimal behavior: short silence so callers don't hear ring then hangup -->\n" +
                "      <action application=\"playback\" data=\"silence_stream://200\"/>\n" +
                "      <action application=\"hangup\"/>\n" +
                "    </condition>\n" +
                "  </extension>\n" +
                "</include>\n";
    }

    /**
     * Minimal freeswitch directory (no agents yet)
     */
    public String buildBootstrapDirectory() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<include>\n" +
                "  <!-- directory is empty for bootstrap: add users dynamically later -->\n" +
                "</include>\n";
    }

    /**
     * Minimal event_socket config (for FS CLI / ESL)
     */
    public String buildEventSocketConf(String password) {
        String pwd = password == null || password.isBlank() ? "ClueCon" : password;
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<configuration name=\"event_socket.conf\" description=\"Event Socket\">\n" +
                "  <settings>\n" +
                "    <param name=\"listen-ip\" value=\"0.0.0.0\"/>\n" +
                "    <param name=\"listen-port\" value=\"8021\"/>\n" +
                "    <param name=\"password\" value=\"" + pwd + "\"/>\n" +
                "    <param name=\"apply-inbound-acl\" value=\"localnet.auto\"/>\n" +
                "  </settings>\n" +
                "</configuration>\n";
    }
}
