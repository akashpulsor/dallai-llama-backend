package com.dalai.llama.preprod.service.assembly;

import com.dalai.llama.preprod.domain.ShotType;
import com.dalai.llama.preprod.service.PreProductionException;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Builds the {@code ShotType -> strategy} routing table once at startup from whatever strategy
 * beans exist -- the only place that would need a change if two strategies ever claimed the same
 * type (it fails fast at startup, not at dispatch time). */
@Component
public class ShotContextAssemblyStrategyResolver {

    private final Map<ShotType, ShotContextAssemblyStrategy> strategiesByType;

    public ShotContextAssemblyStrategyResolver(List<ShotContextAssemblyStrategy> strategies) {
        Map<ShotType, ShotContextAssemblyStrategy> map = new EnumMap<>(ShotType.class);
        for (ShotContextAssemblyStrategy strategy : strategies) {
            for (ShotType type : strategy.supportedTypes()) {
                ShotContextAssemblyStrategy existing = map.put(type, strategy);
                if (existing != null) {
                    throw new IllegalStateException("Two ShotContextAssemblyStrategy beans both claim " + type);
                }
            }
        }
        this.strategiesByType = map;
    }

    public ShotContextAssemblyStrategy resolve(ShotType shotType) {
        ShotContextAssemblyStrategy strategy = strategiesByType.get(shotType);
        if (strategy == null) {
            throw PreProductionException.badRequest("No ShotContextAssemblyStrategy registered for shot type " + shotType);
        }
        return strategy;
    }
}
