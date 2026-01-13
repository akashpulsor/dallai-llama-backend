package com.dalai.llama.pbx.core.util;

import com.dalai.llama.pbx.core.model.Trunk;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Builds dispatcher.list for Kamailio (used for outbound trunk selection).
 */
@Component
public class DynamicKamailioDispatcherBuilder {

    public String buildDispatcher(List<Trunk> trunks) {
        StringBuilder sb = new StringBuilder();
        int id = 1;
        if (trunks == null || trunks.isEmpty()) {
            sb.append("# no trunks\n");
            sb.append("1 sip:blank.invalid\n");
            return sb.toString();
        }
        for (Trunk t : trunks) {
            String host = extractHost(t.getSipUri());
            sb.append(id++).append(" sip:").append(host).append("\n");
        }
        return sb.toString();
    }

    private String extractHost(String sipUri) {
        if (sipUri == null) return "";
        String u = sipUri.startsWith("sip:") ? sipUri.substring(4) : sipUri;
        if (u.contains("@")) u = u.substring(u.indexOf('@') + 1);
        if (u.contains(";")) u = u.substring(0, u.indexOf(';'));
        if (u.contains("?")) u = u.substring(0, u.indexOf('?'));
        return u;
    }
}
