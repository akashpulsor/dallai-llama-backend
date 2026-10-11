package com.dalai.llama.tenant.leadmanagement.inquiry;

import com.dalai.llama.tenant.showcase.client.UpstreamUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Creates a brief in creative-planning-service on the creator's behalf (mesh-internal). */
@Slf4j
@Component
public class CreativePlanningBriefClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    /** A starting length; the creator edits it on the brief before sending it for payment. */
    private static final int DEFAULT_DURATION_SECONDS = 30;

    private final WebClient webClient;

    public CreativePlanningBriefClient(
            WebClient.Builder builder,
            @Value("${services.creative-planning.url:http://creative-planning-service.apps.svc.cluster.local:8080}") String baseUrl) {
        this.webClient = builder.baseUrl(baseUrl).build();
    }

    public record CreatedBrief(UUID requirementId, String shareToken) {
    }

    /** Wire shape of creative-planning's CreateStandaloneRequirementRequest (no product, no files). */
    record StandaloneBrief(String briefText, String targetAudience, String campaignDirection, Integer durationSeconds,
                           List<String> languages, String tenantType, Map<String, String> brandContext, Map<String, String> productDetails) {
    }

    public CreatedBrief createBrief(UUID tenantId, String briefText, String brandName, String industry,
                                    com.dalai.llama.tenant.leadmanagement.inquiry.InquiryDtos.BriefDetails details) {
        return createBrief(tenantId, briefText, brandName, industry, details, List.of());
    }

    /** With images: sent as multipart (product images only when there's a product to hang them on). */
    public CreatedBrief createBrief(UUID tenantId, String briefText, String brandName, String industry,
                                    com.dalai.llama.tenant.leadmanagement.inquiry.InquiryDtos.BriefDetails details,
                                    List<InquiryImageStore.Image> images) {
        Map<String, String> brand = brandName == null || brandName.isBlank() ? null : industry == null
                ? Map.of("brandName", brandName) : Map.of("brandName", brandName, "industry", industry);
        // A product needs a brand to belong to (creative-planning rule), so it only rides along with one.
        Map<String, String> product = brand == null || details == null || details.productName() == null ? null
                : details.productDescription() == null ? Map.of("name", details.productName())
                : Map.of("name", details.productName(), "description", details.productDescription());
        StandaloneBrief body = new StandaloneBrief(briefText,
                details == null ? null : details.targetAudience(), details == null ? null : details.campaignDirection(),
                details != null && details.durationSeconds() != null ? details.durationSeconds() : DEFAULT_DURATION_SECONDS,
                details != null && !details.languages().isEmpty() ? details.languages() : List.of("English"),
                "AI_VIDEO_CREATOR", brand, product);
        try {
            var request = webClient.post().uri("/api/v1/internal/tenants/{tenantId}/project-requirements/standalone", tenantId);
            var spec = images == null || images.isEmpty() ? request.bodyValue(body) : request
                    .contentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA)
                    .body(org.springframework.web.reactive.function.BodyInserters.fromMultipartData(multipart(body, images)));
            CreatedBrief created = spec
                    .retrieve()
                    .bodyToMono(CreatedBrief.class)
                    .block(TIMEOUT);
            if (created == null) throw new UpstreamUnavailableException("Briefs are unavailable right now", null);
            return created;
        } catch (UpstreamUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("Could not create a brief for tenant {}: {}", tenantId, e.getMessage(), e);
            throw new UpstreamUnavailableException("Briefs are unavailable right now; please try again", e);
        }
    }

    private static org.springframework.util.MultiValueMap<String, org.springframework.http.HttpEntity<?>> multipart(StandaloneBrief body,
                                                                                                                List<InquiryImageStore.Image> images) {
        org.springframework.http.client.MultipartBodyBuilder parts = new org.springframework.http.client.MultipartBodyBuilder();
        parts.part("data", body, org.springframework.http.MediaType.APPLICATION_JSON);
        for (InquiryImageStore.Image image : images) {
            if (image.kind() == InquiryImageStore.Kind.PRODUCT && body.productDetails() == null) continue;
            String part = image.kind() == InquiryImageStore.Kind.PRODUCT ? "productImages" : "projectReferenceImages";
            org.springframework.core.io.ByteArrayResource file = new org.springframework.core.io.ByteArrayResource(image.bytes()) {
                @Override
                public String getFilename() {
                    return image.fileName() == null ? "image" : image.fileName();
                }
            };
            parts.part(part, file, org.springframework.http.MediaType.parseMediaType(image.contentType()));
        }
        return parts.build();
    }
}
