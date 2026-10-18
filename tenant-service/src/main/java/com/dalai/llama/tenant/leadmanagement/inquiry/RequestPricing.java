package com.dalai.llama.tenant.leadmanagement.inquiry;

import com.dalai.llama.tenant.showcase.domain.entity.CreatorPublicProfile;
import com.dalai.llama.tenant.showcase.repository.CreatorPublicProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * What the brief made from a brand's request costs, and how much of it is due upfront. Sent to
 * Dalaillama: the platform's rate (its 60-second price, per second), with the platform's upfront share.
 * Sent to a creator: the creator's own rate (priced by billing) with the creator's own share.
 */
@Component
@RequiredArgsConstructor
public class RequestPricing {

    private final RequestPricingProperties properties;
    private final CreatorPublicProfileRepository profileRepository;

    /** {@code pricePerSecond} null means "the creator's own rate". */
    public record Price(BigDecimal pricePerSecond, int prepaymentPercent) {
    }

    public Price of(BrandInquiry inquiry) {
        if (inquiry.getSource() == BrandInquiry.Source.LANDING) {
            return new Price(properties.platformPricePerSecond(), properties.platformPrepaymentPercent());
        }
        return new Price(null, prepaymentPercent(inquiry.getTenantId()));
    }

    public int prepaymentPercent(UUID creatorTenantId) {
        return profileRepository.findById(creatorTenantId).map(CreatorPublicProfile::getRequestPrepaymentPercent)
                .orElseThrow(() -> new IllegalArgumentException("No public profile for this account yet"));
    }

    /** The creator's own upfront share for requests sent to them. */
    @Transactional
    public int setPrepaymentPercent(UUID creatorTenantId, int percent) {
        CreatorPublicProfile profile = profileRepository.findById(creatorTenantId)
                .orElseThrow(() -> new IllegalArgumentException("No public profile for this account yet"));
        profile.setRequestPrepaymentPercent(percent);
        return profileRepository.save(profile).getRequestPrepaymentPercent();
    }
}
