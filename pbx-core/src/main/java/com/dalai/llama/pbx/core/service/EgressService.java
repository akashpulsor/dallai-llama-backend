package com.dalai.llama.pbx.core.service;

import com.dalai.llama.pbx.core.dto.OutboundCallRequest;
import com.dalai.llama.pbx.core.dto.OutboundRouteResponse;
import com.dalai.llama.pbx.core.model.Trunk;
import com.dalai.llama.pbx.core.repository.TrunkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class EgressService {

    private final TrunkRepository trunkRepo;

    public OutboundRouteResponse selectTrunk(OutboundCallRequest req) {
        List<Trunk> trunks = trunkRepo.findByTenantIdAndEnabledTrue(req.getTenantId());
        if (trunks.isEmpty()) {
            return OutboundRouteResponse.builder().decision("reject").build();
        }
        // Simple region-aware selection: prefer trunks with region match; fallback first
        Trunk chosen = trunks.stream()
                .sorted(Comparator.comparing(t -> t.getRegion()==null?1:0)) // prefer with region
                .findFirst().get();

        String cli = null;//req.getCli()!=null? req.getCli() : null;
        return OutboundRouteResponse.builder()
                .decision("deliver")
                .trunkId(chosen.getId())
                .trunkUri(chosen.getSipUri())
                .cli(cli)
                .build();
    }
}
