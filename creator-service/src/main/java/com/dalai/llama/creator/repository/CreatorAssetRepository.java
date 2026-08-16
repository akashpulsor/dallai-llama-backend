package com.dalai.llama.creator.repository;

import com.dalai.llama.creator.domain.entity.CreatorAsset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CreatorAssetRepository extends JpaRepository<CreatorAsset, UUID> {

    @Query(
            value = """
                    select *
                    from creator_assets
                    where tenant_id = :tenantId
                      and user_id = :userId
                      and metadata ->> 'scriptId' = cast(:scriptId as text)
                      and asset_type in ('STORYBOARD_IMAGE', 'PRODUCT_VISUAL_ANCHOR', 'LIGHTING_BUILD_SHEET_IMAGE', 'CAMERA_PLAN_SHEET_IMAGE')
                    order by
                      nullif(regexp_replace(coalesce(metadata ->> 'shotNumber', ''), '[^0-9]', '', 'g'), '')::int asc nulls last,
                      created_at desc
                    """,
            nativeQuery = true
    )
    List<CreatorAsset> findShotImageAssetsForScript(
            @Param("scriptId") UUID scriptId,
            @Param("tenantId") String tenantId,
            @Param("userId") String userId
    );

    List<CreatorAsset> findByTenantIdAndUserIdAndScriptIdAndAssetTypeOrderByShotNumberAsc(
            String tenantId,
            String userId,
            UUID scriptId,
            String assetType
    );

    Optional<CreatorAsset> findByTenantIdAndUserIdAndRunIdAndShotNumberAndAssetType(
            String tenantId,
            String userId,
            UUID runId,
            Integer shotNumber,
            String assetType
    );

    Optional<CreatorAsset> findByTenantIdAndUserIdAndRunIdAndCombinedTrue(
            String tenantId,
            String userId,
            UUID runId
    );
}
