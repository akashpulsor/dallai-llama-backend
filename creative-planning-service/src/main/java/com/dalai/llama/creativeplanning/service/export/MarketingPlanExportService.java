package com.dalai.llama.creativeplanning.service.export;

import com.dalai.llama.creativeplanning.domain.entity.BrandContext;
import com.dalai.llama.creativeplanning.domain.entity.MarketingPlan;
import com.dalai.llama.creativeplanning.dto.BrandPlanExportView;
import com.dalai.llama.creativeplanning.service.BrandContextService;
import com.dalai.llama.creativeplanning.service.marketingplan.MarketingPlanGenerationService;
import com.dalai.llama.creativeplanning.service.storage.MinioObjectStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Branded PDF export of a full marketing plan -- reuses the same {@link PdfDocumentWriter} as
 * {@link BrandPlanExportService}, just a different section layout (the plan's eleven sections
 * instead of a locked idea's five). */
@Service
public class MarketingPlanExportService {

    private final MarketingPlanGenerationService marketingPlanGenerationService;
    private final BrandContextService brandContextService;
    private final MinioObjectStorage minioObjectStorage;
    private final String exportPrefix;

    public MarketingPlanExportService(
            MarketingPlanGenerationService marketingPlanGenerationService,
            BrandContextService brandContextService,
            MinioObjectStorage minioObjectStorage,
            @Value("${creative-planning.minio.export-prefix}") String exportPrefix
    ) {
        this.marketingPlanGenerationService = marketingPlanGenerationService;
        this.brandContextService = brandContextService;
        this.minioObjectStorage = minioObjectStorage;
        this.exportPrefix = exportPrefix;
    }

    @Transactional(readOnly = true)
    public BrandPlanExportView exportPdf(UUID tenantId, UUID planId) {
        MarketingPlan plan = marketingPlanGenerationService.require(tenantId, planId);
        BrandContext brand = brandContextService.requireBrand(tenantId, plan.getBrandContextId());

        byte[] pdfBytes = render(brand, plan);

        String objectKey = "%s/%s/%s.pdf".formatted(exportPrefix, planId, UUID.randomUUID());
        minioObjectStorage.uploadBytes(objectKey, pdfBytes, "application/pdf");
        return new BrandPlanExportView(minioObjectStorage.bucket(), objectKey, minioObjectStorage.signedUrl(objectKey));
    }

    private byte[] render(BrandContext brand, MarketingPlan plan) {
        try (PdfDocumentWriter writer = new PdfDocumentWriter()) {
            writer.title(brand.getBrandName() + " -- Marketing Plan");

            writer.heading("Executive Summary");
            writer.paragraph(orNone(plan.getExecutiveSummary()));
            writer.spacer();

            writer.heading("Market Analysis");
            writer.paragraph(orNone(plan.getMarketAnalysis()));
            writer.spacer();

            writer.heading("Target Audience Profile");
            writer.paragraph(orNone(plan.getTargetAudienceProfile()));
            writer.spacer();

            writer.heading("Positioning Statement");
            writer.paragraph(orNone(plan.getPositioningStatement()));
            writer.spacer();

            writer.heading("Brand Strategy");
            writer.paragraph(orNone(plan.getBrandStrategy()));
            writer.spacer();

            writer.heading("Marketing Objectives");
            writer.paragraph(orNone(plan.getMarketingObjectives()));
            writer.spacer();

            writer.heading("Channel Strategy");
            writer.paragraph(orNone(plan.getChannelStrategy()));
            writer.spacer();

            writer.heading("Content Strategy");
            writer.paragraph(orNone(plan.getContentStrategy()));
            writer.spacer();

            writer.heading("Budget Guidance");
            writer.paragraph(orNone(plan.getBudgetGuidance()));
            writer.spacer();

            writer.heading("Success Metrics");
            writer.paragraph(orNone(plan.getSuccessMetrics()));
            writer.spacer();

            writer.heading("Risks and Mitigations");
            writer.paragraph(orNone(plan.getRisksAndMitigations()));
            writer.spacer();

            writer.heading("Case Studies Referenced");
            writer.paragraph(orNone(plan.getReferencedCaseStudyPatterns()));

            return writer.toBytes();
        }
    }

    private String orNone(String value) {
        return value == null || value.isBlank() ? "Not specified" : value;
    }
}
