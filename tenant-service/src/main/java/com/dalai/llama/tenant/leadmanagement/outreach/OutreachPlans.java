package com.dalai.llama.tenant.leadmanagement.outreach;

import com.dalai.llama.tenant.service.client.ProductServiceClient;
import com.dalai.llama.tenant.service.client.ProductServiceClient.OutreachCaps;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A creator's outreach caps come with their video plan (product-service): Free 100 emails a month
 * to 10 brands, Studio 1,000 to 100, Pro 3,000 to every brand. Read once every few minutes per
 * creator, not per recipient; when product-service can't answer, the free plan's caps apply.
 */
@Component
public class OutreachPlans {

    static final Duration FRESH_FOR = Duration.ofMinutes(5);

    private final ProductServiceClient products;
    private final OutreachProperties properties;
    private final Clock clock;
    private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(OutreachCaps caps, Instant readAt) {
    }

    public OutreachPlans(ProductServiceClient products, OutreachProperties properties, Clock clock) {
        this.products = products;
        this.properties = properties;
        this.clock = clock;
    }

    public OutreachCaps caps(UUID tenantId) {
        Instant now = clock.instant();
        Cached cached = cache.get(tenantId);
        if (cached != null && cached.readAt().plus(FRESH_FOR).isAfter(now)) return cached.caps();
        OutreachCaps caps = products.creatorVideoOutreachCaps(tenantId).orElseGet(this::free);
        cache.put(tenantId, new Cached(caps, now));
        return caps;
    }

    private OutreachCaps free() {
        return new OutreachCaps(properties.freePerMonth(), FREE_BRAND_REACH);
    }

    static final int FREE_BRAND_REACH = 10;
}
