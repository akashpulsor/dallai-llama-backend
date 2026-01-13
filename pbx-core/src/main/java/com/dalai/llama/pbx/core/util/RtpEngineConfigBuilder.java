package com.dalai.llama.pbx.core.util;

import org.springframework.stereotype.Component;

/**
 * Minimal RTPengine config for tenant bootstrap.
 */
@Component
public class RtpEngineConfigBuilder {

    public String build(String tenantId) {
        // Keep default ports, small timeout for bootstrap
        return String.join("\n",
                "# RTPengine config for tenant " + tenantId,
                "table=0",
                "listen-ng=0.0.0.0:22222",
                "listen-udp=0.0.0.0:10000",
                "timeout=60",
                "log-level=6",
                "log-stderr",
                ""
        );
    }
}
