package com.dalai.llama.pbx.core.util;

import com.dalai.llama.pbx.core.dto.AgentDto;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Builds FreeSWITCH directory XML dynamically from DB Agent records.
 */
@Component
public class DynamicFreeSwitchDirectoryBuilder {

    public String buildDirectoryXml(String tenantId, List<AgentDto> agents, String realm) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<include>\n");

        if (agents == null || agents.isEmpty()) {
            sb.append("  <!-- no users for tenant ").append(tenantId).append(" -->\n");
        } else {
            for (AgentDto a : agents) {
                String user = a.getSipUser();
                String pwd  = a.getSipPassword();
                sb.append("  <user id=\"").append(escape(user)).append("\">\n");
                sb.append("    <params>\n");
                sb.append("      <param name=\"password\" value=\"").append(escape(pwd)).append("\"/>\n");
                sb.append("      <param name=\"dial-string\" value=\"{presence_id=${dialed_user}@")
                        .append(escape(realm))
                        .append("}${sofia_contact(${dialed_user}@")
                        .append(escape(realm)).append(")}\"/>\n");
                sb.append("    </params>\n");
                sb.append("    <variables>\n");
                sb.append("      <variable name=\"tenant_id\" value=\"").append(escape(tenantId)).append("\"/>\n");
                sb.append("      <variable name=\"user_context\" value=\"default\"/>\n");
                // add agent metadata (queue, agent_id)
                if (a.getAgentId() != null) {
                    sb.append("      <variable name=\"agent_id\" value=\"").append(escape(a.getAgentId())).append("\"/>\n");
                }
                sb.append("    </variables>\n");
                sb.append("  </user>\n");
            }
        }

        sb.append("</include>\n");
        return sb.toString();
    }

    public String buildDirectoryXml(String tenantId) {

        String domain = "tenant-%s.internal".formatted(tenantId);

        return """
                <include>
                  <!-- Directory for Tenant %s -->
                  
                  <domain name="%s">
                    
                    <!-- Agent auth -->
                    <params>
                      <param name="jsonrpc-allow-calls" value="true"/>
                      <param name="jsonrpc-allow-auth" value="true"/>
                      <param name="jsonrpc-server-url" value="http://agent-service.%s.svc.cluster.local:8080/auth"/>
                    </params>
                    
                    <!-- Agents will be added dynamically via XML-RPC (fs_cli lua) -->
                    <groups>
                      <group name="agents">
                        <users>
                          <!-- No static agents. Added by runtime update -->
                        </users>
                      </group>

                      <group name="supervisors">
                        <users>
                        </users>
                      </group>
                    </groups>
                    
                  </domain>
                </include>
        """.formatted(
                tenantId,
                domain,
                "tenant-" + tenantId
        );
    }
    private String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<","&lt;").replace(">","&gt;");
    }
}
