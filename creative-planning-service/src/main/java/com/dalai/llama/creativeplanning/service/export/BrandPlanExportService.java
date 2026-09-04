package com.dalai.llama.creativeplanning.service.export;

import com.dalai.llama.creativeplanning.domain.entity.BrandContext;
import com.dalai.llama.creativeplanning.domain.entity.CampaignPlanningSession;
import com.dalai.llama.creativeplanning.domain.entity.LockedIdea;
import com.dalai.llama.creativeplanning.domain.entity.ProductProfile;
import com.dalai.llama.creativeplanning.dto.BrandPlanExportView;
import com.dalai.llama.creativeplanning.repository.CampaignPlanningSessionRepository;
import com.dalai.llama.creativeplanning.repository.LockedIdeaRepository;
import com.dalai.llama.creativeplanning.repository.ProductProfileRepository;
import com.dalai.llama.creativeplanning.service.BrandContextService;
import com.dalai.llama.creativeplanning.service.CreativePlanningException;
import com.dalai.llama.creativeplanning.service.storage.MinioObjectStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Closes the design doc's §10.8: "from brand marketing plan, we can create pdf/ppt branded for it
 * that use it." PDF only in this pass -- a PPTX renderer would need Apache POI and a different
 * writer, but the content model (brand / product / locked-idea sections) is identical, so adding
 * it later is a new writer implementation, not a redesign.
 */
@Service
public class BrandPlanExportService {

    private final LockedIdeaRepository lockedIdeaRepository;
    private final CampaignPlanningSessionRepository campaignPlanningSessionRepository;
    private final ProductProfileRepository productProfileRepository;
    private final BrandContextService brandContextService;
    private final MinioObjectStorage minioObjectStorage;
    private final String exportPrefix;

    public BrandPlanExportService(
            LockedIdeaRepository lockedIdeaRepository,
            CampaignPlanningSessionRepository campaignPlanningSessionRepository,
            ProductProfileRepository productProfileRepository,
            BrandContextService brandContextService,
            MinioObjectStorage minioObjectStorage,
            @Value("${creative-planning.minio.export-prefix}") String exportPrefix
    ) {
        this.lockedIdeaRepository = lockedIdeaRepository;
        this.campaignPlanningSessionRepository = campaignPlanningSessionRepository;
        this.productProfileRepository = productProfileRepository;
        this.brandContextService = brandContextService;
        this.minioObjectStorage = minioObjectStorage;
        this.exportPrefix = exportPrefix;
    }

    @Transactional
    public BrandPlanExportView exportPdf(UUID tenantId, UUID lockedIdeaId) {
        LockedIdea idea = lockedIdeaRepository.findByIdAndTenantId(lockedIdeaId, tenantId)
                .orElseThrow(() -> CreativePlanningException.notFound("No locked idea " + lockedIdeaId));
        CampaignPlanningSession session = campaignPlanningSessionRepository.findByIdAndTenantId(idea.getSessionId(), tenantId)
                .orElseThrow(() -> CreativePlanningException.notFound("No campaign session for locked idea " + lockedIdeaId));
        BrandContext brand = brandContextService.requireBrand(tenantId, session.getBrandContextId());
        ProductProfile product = session.getProductProfileId() == null ? null
                : productProfileRepository.findByIdAndTenantId(session.getProductProfileId(), tenantId).orElse(null);

        byte[] pdfBytes = render(brand, product, idea);

        String objectKey = "%s/%s/%s.pdf".formatted(exportPrefix, lockedIdeaId, UUID.randomUUID());
        minioObjectStorage.uploadBytes(objectKey, pdfBytes, "application/pdf");
        return new BrandPlanExportView(minioObjectStorage.bucket(), objectKey, minioObjectStorage.signedUrl(objectKey));
    }

    private byte[] render(BrandContext brand, ProductProfile product, LockedIdea idea) {
        try (PdfDocumentWriter writer = new PdfDocumentWriter()) {
            writer.title(brand.getBrandName() + " -- " + idea.getTitle());

            writer.heading("Brand");
            writer.paragraph("Industry: " + orNone(brand.getIndustry()));
            writer.paragraph("Voice: " + orNone(brand.getBrandVoice()));
            writer.paragraph("Values: " + orNone(brand.getBrandValues()));
            writer.spacer();

            if (product != null) {
                writer.heading("Product");
                writer.paragraph(product.getCategory() == null ? product.getName() : product.getName() + " (" + product.getCategory() + ")");
                writer.paragraph(orNone(product.getDescription()));
                writer.spacer();
            }

            writer.heading("Campaign Concept");
            writer.paragraph(orNone(idea.getConcept()));
            writer.spacer();

            writer.heading("Campaign Angle");
            writer.paragraph(orNone(idea.getCampaignAngle()));
            writer.spacer();

            writer.heading("Key Message");
            writer.paragraph(orNone(idea.getKeyMessage()));
            writer.spacer();

            writer.heading("Target Audience");
            writer.paragraph(orNone(idea.getTargetAudience()));
            writer.spacer();

            writer.heading("Tone");
            writer.paragraph(orNone(idea.getTone()));
            writer.spacer();

            writer.heading("Budget Tier");
            writer.paragraph(idea.getBudgetTier().name());

            return writer.toBytes();
        }
    }

    private String orNone(String value) {
        return value == null || value.isBlank() ? "Not specified" : value;
    }
}
