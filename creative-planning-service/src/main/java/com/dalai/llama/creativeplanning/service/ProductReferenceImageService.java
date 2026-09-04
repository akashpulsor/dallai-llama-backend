package com.dalai.llama.creativeplanning.service;

import com.dalai.llama.creativeplanning.domain.entity.BrandContext;
import com.dalai.llama.creativeplanning.domain.entity.ProductProfile;
import com.dalai.llama.creativeplanning.domain.entity.ProductReferenceImage;
import com.dalai.llama.creativeplanning.dto.ProductReferenceImageView;
import com.dalai.llama.creativeplanning.repository.ProductReferenceImageRepository;
import com.dalai.llama.creativeplanning.service.storage.MinioObjectStorage;
import com.dalai.llama.creativeplanning.service.storage.UploadValidation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Owns the "Pinterest-image flow": stream the upload straight to MinIO via {@link
 * MinioObjectStorage} (never buffered whole into a byte[]), then trigger a synchronous vision
 * analysis using the multipart file's own bytes (images are small and uploaded once, unlike the
 * large-video case that streaming discipline exists for).
 */
@Service
public class ProductReferenceImageService {

    private final ProductReferenceImageRepository productReferenceImageRepository;
    private final ProductProfileService productProfileService;
    private final BrandContextService brandContextService;
    private final ReferenceImageAnalysisService referenceImageAnalysisService;
    private final MinioObjectStorage minioObjectStorage;
    private final String referenceImagePrefix;
    private final long maxUploadSizeBytes;

    public ProductReferenceImageService(
            ProductReferenceImageRepository productReferenceImageRepository,
            ProductProfileService productProfileService,
            BrandContextService brandContextService,
            ReferenceImageAnalysisService referenceImageAnalysisService,
            MinioObjectStorage minioObjectStorage,
            @Value("${creative-planning.minio.reference-image-prefix}") String referenceImagePrefix,
            @Value("${creative-planning.minio.max-upload-size-mb}") long maxUploadSizeMb
    ) {
        this.productReferenceImageRepository = productReferenceImageRepository;
        this.productProfileService = productProfileService;
        this.brandContextService = brandContextService;
        this.referenceImageAnalysisService = referenceImageAnalysisService;
        this.minioObjectStorage = minioObjectStorage;
        this.referenceImagePrefix = referenceImagePrefix;
        this.maxUploadSizeBytes = maxUploadSizeMb * 1024 * 1024;
    }

    @Transactional
    public ProductReferenceImageView upload(UUID tenantId, UUID productId, MultipartFile file) {
        ProductProfile product = productProfileService.requireProduct(tenantId, productId);
        ProductReferenceImage image = store(tenantId, productId, file);
        BrandContext brand = brandContextService.requireBrand(tenantId, product.getBrandContextId());
        String dataUri = toDataUri(file, UploadValidation.contentTypeOrDefault(file, "image/jpeg"));
        var analysis = referenceImageAnalysisService.analyze(tenantId, image.getId(), dataUri, brand);
        return new ProductReferenceImageView(image.getId(), image.getProductProfileId(), image.getBucket(), image.getObjectKey(),
                minioObjectStorage.signedUrl(image.getObjectKey()), analysis);
    }

    /** Stores the image without analyzing it -- used when a product is created inline while
     * starting a standalone requirement, where analysis is deferred until the requirement is
     * funded (see {@code ReferenceMaterialAnalysisService}), unlike {@link #upload} above which
     * analyzes synchronously for the full brand/campaign journey. */
    @Transactional
    public ProductReferenceImageView storeWithoutAnalysis(UUID tenantId, UUID productId, MultipartFile file) {
        ProductReferenceImage image = store(tenantId, productId, file);
        return new ProductReferenceImageView(image.getId(), image.getProductProfileId(), image.getBucket(), image.getObjectKey(),
                minioObjectStorage.signedUrl(image.getObjectKey()), null);
    }

    private ProductReferenceImage store(UUID tenantId, UUID productId, MultipartFile file) {
        ProductProfile product = productProfileService.requireProduct(tenantId, productId);
        UploadValidation.requireNonEmpty(file, "image");
        UploadValidation.requireWithinSize(file, maxUploadSizeBytes);
        String contentType = UploadValidation.contentTypeOrDefault(file, "image/jpeg");
        String objectKey = "%s/%s/%s.%s".formatted(
                referenceImagePrefix, productId, UUID.randomUUID(), UploadValidation.extensionFor(contentType));

        minioObjectStorage.uploadMultipart(objectKey, file, contentType);

        return productReferenceImageRepository.save(ProductReferenceImage.builder()
                .tenantId(tenantId)
                .productProfileId(product.getId())
                .bucket(minioObjectStorage.bucket())
                .objectKey(objectKey)
                .createdAt(OffsetDateTime.now())
                .build());
    }

    @Transactional(readOnly = true)
    public List<ProductReferenceImageView> list(UUID tenantId, UUID productId) {
        productProfileService.requireProduct(tenantId, productId);
        return productReferenceImageRepository.findByProductProfileId(productId).stream()
                .map(image -> new ProductReferenceImageView(image.getId(), image.getProductProfileId(), image.getBucket(),
                        image.getObjectKey(), minioObjectStorage.signedUrl(image.getObjectKey()),
                        referenceImageAnalysisService.getIfPresent(image.getId())))
                .collect(Collectors.toList());
    }

    private String toDataUri(MultipartFile file, String contentType) {
        try {
            return "data:" + contentType + ";base64," + Base64.getEncoder().encodeToString(file.getBytes());
        } catch (Exception ex) {
            throw CreativePlanningException.badRequest("Could not read uploaded image: " + ex.getMessage());
        }
    }
}
