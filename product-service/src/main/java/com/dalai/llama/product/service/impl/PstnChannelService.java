package com.dalai.llama.product.service.impl;


import com.dalai.llama.product.domain.entity.PstnChannelBundle;
import com.dalai.llama.product.domain.entity.SipTrunk;
import com.dalai.llama.product.domain.entity.enums.ChannelDirection;
import com.dalai.llama.product.repository.PstnChannelBundleRepository;
import com.dalai.llama.product.repository.SipTrunkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PstnChannelService {

    private final PstnChannelBundleRepository repository;
    private final SipTrunkRepository sipTrunkRepository;

    @Transactional
    public PstnChannelBundle createForSubscription(
            UUID tenantId,
            UUID subscriptionId,
            String productCode,
            int totalChannels,
            Integer requestedInbound,
            Integer requestedOutbound) {

        // Determine direction based on product
        ChannelDirection direction = getDirectionForProduct(productCode);

        // Calculate channels based on direction
        Integer inbound = null;
        Integer outbound = null;

        switch (direction) {
            case INBOUND:
                inbound = totalChannels;
                break;
            case OUTBOUND:
                outbound = totalChannels;
                break;
            case BOTH:
                if (requestedInbound != null && requestedOutbound != null) {
                    // Customer specified split
                    inbound = requestedInbound;
                    outbound = requestedOutbound;
                } else {
                    // Default 60/40 split
                    inbound = (int) Math.ceil(totalChannels * 0.6);
                    outbound = totalChannels - inbound;
                }
                break;
        }

        // Get platform trunk
        SipTrunk trunk = sipTrunkRepository.findPlatformTrunk().orElse(null);

        PstnChannelBundle bundle = PstnChannelBundle.builder()
                .tenantId(tenantId)
                .subscriptionId(subscriptionId)
                .productCode(productCode)
                .sipTrunk(trunk)
                .direction(direction)
                .totalChannels(totalChannels)
                .inboundChannels(inbound)
                .outboundChannels(outbound)
                .activeChannels(0)
                .status("ACTIVE")
                .build();

        bundle = repository.save(bundle);
        log.info("Created channel bundle for tenant {} - product: {}, direction: {}, total: {}",
                tenantId, productCode, direction, totalChannels);

        return bundle;
    }

    private ChannelDirection getDirectionForProduct(String productCode) {
        return switch (productCode) {
            case "CONV_IVR" -> ChannelDirection.INBOUND;
            case "OUTBOUND_DIALER" -> ChannelDirection.OUTBOUND;
            default -> ChannelDirection.BOTH;  // AI_CC, BASIC_PBX
        };
    }
}
