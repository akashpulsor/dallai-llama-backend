package com.dalai.llama.pbx.core.util;

import org.springframework.stereotype.Component;

/**
 * Builds simple conference dialplan extension for tenant.
 */
@Component
public class ConferenceBuilder {

    public String buildConferenceXml(String tenantId) {
        StringBuilder sb = new StringBuilder();
        sb.append("<extension name=\"conference-").append(tenantId).append("\">\n");
        sb.append("  <condition field=\"destination_number\" expression=\"^conf-(.*)$\">\n");
        sb.append("    <action application=\"answer\"/>\n");
        sb.append("    <action application=\"conference\" data=\"${destination_number}@tenant-")
                .append(tenantId).append("-conf\"/>\n");
        sb.append("  </condition>\n");
        sb.append("</extension>\n");
        return sb.toString();
    }
}
