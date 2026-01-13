package com.dalai.llama.pbx.core.util;

import com.dalai.llama.pbx.core.model.IvrNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Build IVR context XML from a list of nodes (nodes stored in DB).
 * Each IvrNode is assumed to have: id, prompt (path), dtmf map, botEnabled
 */
@Component
public class IvrTreeBuilder {

    public String buildIvr(String tenantId, List<IvrNode> nodes) {
        StringBuilder sb = new StringBuilder();
        sb.append("<context name=\"ivr-").append(tenantId).append("\">\n");

        if (nodes == null || nodes.isEmpty()) {
            // default main node to say "no IVR configured"
            sb.append("  <extension name=\"main\">\n");
            sb.append("    <condition field=\"destination_number\" expression=\"^main$\">\n");
            sb.append("      <action application=\"playback\" data=\"ivr/default/no-ivr-config.wav\"/>\n");
            sb.append("      <action application=\"hangup\"/>\n");
            sb.append("    </condition>\n");
            sb.append("  </extension>\n");
        } else {
            for (IvrNode node : nodes) {
                sb.append("  <extension name=\"").append(node.getId()).append("\">\n");
                sb.append("    <condition field=\"destination_number\" expression=\"^").append(node.getId()).append("$\">\n");
                sb.append("      <action application=\"playback\" data=\"").append(node.getPrompt()).append("\"/>\n");
                // collect digits
                sb.append("      <action application=\"play_and_get_digits\" data=\"1 1 3 5000 # ")
                        .append(node.getPrompt()).append(" invalid.wav\"/>\n");
                sb.append("      <action application=\"set\" data=\"MENU=${dtmf_digits}\"/>\n");
                Map<String,String> dtmf = node.getDtmf();

                if (dtmf != null) {
                    for (var e : dtmf.entrySet()) {
                        sb.append("      <condition field=\"${MENU}\" expression=\"").append(e.getKey()).append("\">\n");
                        sb.append("        <action application=\"transfer\" data=\"").append(e.getValue())
                                .append(" XML ivr-").append(tenantId).append("\"/>\n");
                        sb.append("      </condition>\n");
                    }
                }
                if (node.isBotEnabled()) {
                    sb.append("      <action application=\"audio_fork\" data=\"ws://ai-service-")
                            .append(tenantId).append(":9000/stream?node=").append(node.getId()).append("\"/>\n");
                }
                sb.append("    </condition>\n");
                sb.append("  </extension>\n");
            }
        }

        sb.append("</context>\n");
        return sb.toString();
    }
}
