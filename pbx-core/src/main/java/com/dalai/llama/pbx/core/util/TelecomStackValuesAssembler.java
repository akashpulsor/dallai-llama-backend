package com.dalai.llama.pbx.core.util;

import com.dalai.llama.pbx.core.model.Did;
import com.dalai.llama.pbx.core.model.SignalingConfig;
import com.dalai.llama.pbx.core.model.Tenant;
import com.dalai.llama.pbx.core.model.Trunk;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Assembles minimal Helm values (bootstrap) for the tenant using the builders above.
 */
@Component
public class TelecomStackValuesAssembler {

    private final KamailioBootstrapConfigBuilder kamBuilder;
    private final FreeSwitchBootstrapBuilder fsBuilder;
    private final FreeSwitchDirectoryBootstrapBuilder dirBuilder;
    private final CoturnConfigBuilder coturnBuilder;
    private final RtpEngineConfigBuilder rtpBuilder;
    private final HelmValuesBuilder yamlBuilder;

    public TelecomStackValuesAssembler(
            KamailioBootstrapConfigBuilder kamBuilder,
            FreeSwitchBootstrapBuilder fsBuilder,
            FreeSwitchDirectoryBootstrapBuilder dirBuilder,
            CoturnConfigBuilder coturnBuilder,
            RtpEngineConfigBuilder rtpBuilder,
            HelmValuesBuilder yamlBuilder
    ) {
        this.kamBuilder = kamBuilder;
        this.fsBuilder = fsBuilder;
        this.dirBuilder = dirBuilder;
        this.coturnBuilder = coturnBuilder;
        this.rtpBuilder = rtpBuilder;
        this.yamlBuilder = yamlBuilder;
    }

    /**
     * Build minimal values.yaml (YAML string) for tenant bootstrap.
     */
    public String assemble(Tenant tenant,
                           SignalingConfig cfg,
                           List<Trunk> trunks,
                           List<Did> dids) throws Exception {

        String tenantId = tenant.getId();

        Map<String, Object> config = new HashMap<>();
        config.put("kamailioCfg", kamBuilder.buildBootstrap(tenant, cfg));
        config.put("freeswitchDialplan", fsBuilder.buildBootstrapFsDialplan(tenantId));
        config.put("freeswitchDirectory", dirBuilder.build());
        config.put("coturnCfg", coturnBuilder.build(tenant, cfg));
        config.put("rtpengineCfg", rtpBuilder.build(tenantId));
        config.put("dispatcher", buildDispatcherSkeleton(trunks));

        Map<String, Object> values = new HashMap<>();
        values.put("tenantId", tenantId);
        values.put("realm", cfg.getAuthRealm());
        values.put("images", Map.of(
                "kamailio", "dalaillama/kamailio:latest",
                "freeswitch", "dalaillama/freeswitch:latest",
                "coturn", "dalaillama/coturn:latest",
                "rtpengine", "dalaillama/rtpengine:latest"
        ));
        values.put("config", config);

        return yamlBuilder.build(values);
    }

    private String buildDispatcherSkeleton(List<Trunk> trunks) {
        if (trunks == null || trunks.isEmpty()) {
            return "# dispatcher skeleton - add trunks later\n";
        }
        StringBuilder sb = new StringBuilder();
        int id = 1;
        for (Trunk t : trunks) {
            sb.append(id++).append(" sip:").append(t.getSipUri()).append("\n");
        }
        return sb.toString();
    }
}
