package com.dalai.llama.pbx.core.util;

import org.springframework.stereotype.Component;

/**
 * Small helper to produce AI audio fork extension.
 */
@Component
public class BotForkBuilder {

    public String buildAIForkXml(String tenantId) {
        StringBuilder sb = new StringBuilder();
        sb.append("<extension name=\"ai-fork-").append(tenantId).append("\">\n");
        sb.append("  <condition field=\"destination_number\" expression=\"^ai-([a-z0-9_-]+)$\">\n");
        sb.append("    <action application=\"answer\"/>\n");
        sb.append("    <action application=\"audio_fork\" data=\"ws://ai-service-")
                .append(tenantId).append(":9000/stream?callid=${uuid}&node=${1}\"/>\n");
        sb.append("  </condition>\n");
        sb.append("</extension>\n");
        return sb.toString();
    }
}
