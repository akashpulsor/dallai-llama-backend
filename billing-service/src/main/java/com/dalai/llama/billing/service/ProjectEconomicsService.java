package com.dalai.llama.billing.service;

import com.dalai.llama.billing.client.CreativePlanningServiceClient;
import com.dalai.llama.billing.client.CreativePlanningServiceClient.ProjectQuote;
import com.dalai.llama.billing.domain.entity.ClientReviewPayment;
import com.dalai.llama.billing.repository.ClientReviewPaymentRepository;
import com.dalai.llama.billing.repository.UsageRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Assembles {@link ProjectEconomics} from the three places a project's money is recorded: the
 * quote (creative-planning), the AI usage charged against it (usage_records, billed and raw), and
 * the client's review-page payments (client_review_payments). Nothing is stored -- every figure is
 * summed from the ledgers on read, so it can never disagree with them.
 * <p>
 * The brief's upfront payment is taken from the quote's own terms (its required amount, once the
 * brief is funded) -- the same figure the review-page balance is computed from, so the two views
 * of "what has the client paid" are one number.
 */
@Service
@RequiredArgsConstructor
public class ProjectEconomicsService {

    private static final String CAPTURED = "SUCCESS";

    private final CreativePlanningServiceClient creativePlanningServiceClient;
    private final VideoPricingService videoPricingService;
    private final UsageRecordRepository usageRecordRepository;
    private final ClientReviewPaymentRepository clientReviewPaymentRepository;

    @Transactional(readOnly = true)
    public ProjectEconomics forProject(UUID tenantId, UUID projectId) {
        Optional<ProjectQuote> quote = creativePlanningServiceClient.getProjectQuote(tenantId, projectId);
        List<ClientReviewPayment> reviewPayments =
                clientReviewPaymentRepository.findByTenantIdAndProjectIdAndStatus(tenantId, projectId, CAPTURED);

        BigDecimal paidUpfront = quote.map(ProjectQuote::paidUpfront).orElse(BigDecimal.ZERO);
        BigDecimal paidOnReview = sum(reviewPayments, ClientReviewPayment::getTotalAmount);
        BigDecimal reviewToCreator = sum(reviewPayments, ClientReviewPayment::getCreatorAmount);
        BigDecimal charged = usageRecordRepository.sumCostByProjectId(tenantId, projectId);
        BigDecimal providerCost = usageRecordRepository.sumRawCostByProjectId(tenantId, projectId);

        BigDecimal creatorReceived = paidUpfront.add(reviewToCreator);
        BigDecimal usageMargin = charged.subtract(providerCost);
        BigDecimal reviewShare = paidOnReview.subtract(reviewToCreator);
        return new ProjectEconomics(
                projectId,
                quote.map(ProjectQuote::quotedCurrency).orElse(CurrencyConversionService.DEFAULT_CURRENCY),
                quote.map(videoPricingService::productionCharges).orElse(null),
                new ProjectEconomics.Customer(paidUpfront, paidOnReview, paidUpfront.add(paidOnReview)),
                new ProjectEconomics.Creator(creatorReceived, charged, creatorReceived.subtract(charged)),
                new ProjectEconomics.Platform(providerCost, charged, usageMargin, reviewShare, usageMargin.add(reviewShare)));
    }

    /** Every project this tenant has spent on or been paid for, newest activity order not
     * guaranteed -- callers sort by what they display. */
    @Transactional(readOnly = true)
    public List<ProjectEconomics> forTenant(UUID tenantId) {
        return Stream.concat(
                        usageRecordRepository.findProjectIdsByTenantId(tenantId).stream(),
                        clientReviewPaymentRepository.findPaidProjectIdsByTenantId(tenantId).stream())
                .distinct()
                .map(projectId -> forProject(tenantId, projectId))
                .toList();
    }

    private static BigDecimal sum(List<ClientReviewPayment> payments, Function<ClientReviewPayment, BigDecimal> amount) {
        return payments.stream().map(amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
