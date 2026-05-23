package com.dalai.llama.creator.repository;

import com.dalai.llama.creator.domain.entity.CreatorTrend;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface CreatorTrendRepository extends JpaRepository<CreatorTrend, UUID> {

    @Query(
            value = """
                    select distinct t.*
                    from creator_trends t
                    left join creator_trend_category_map m on m.trend_id = t.id
                    left join creator_categories c on c.id = m.category_id
                    where t.status = 'ACTIVE'
                      and (:platform is null or t.platform_code = :platform)
                      and (:country is null or t.country_code = :country)
                      and (:category is null or t.category_code = :category or c.code = :category)
                      and (cast(:since as timestamptz) is null or t.last_seen_at >= cast(:since as timestamptz))
                    order by t.score desc, t.velocity desc, t.last_seen_at desc
                    """,
            countQuery = """
                    select count(distinct t.id)
                    from creator_trends t
                    left join creator_trend_category_map m on m.trend_id = t.id
                    left join creator_categories c on c.id = m.category_id
                    where t.status = 'ACTIVE'
                      and (:platform is null or t.platform_code = :platform)
                      and (:country is null or t.country_code = :country)
                      and (:category is null or t.category_code = :category or c.code = :category)
                      and (cast(:since as timestamptz) is null or t.last_seen_at >= cast(:since as timestamptz))
                    """,
            nativeQuery = true
    )
    Page<CreatorTrend> findRankedTrends(
            @Param("platform") String platform,
            @Param("category") String category,
            @Param("country") String country,
            @Param("since") OffsetDateTime since,
            Pageable pageable
    );

    List<CreatorTrend> findTop20ByCategoryCodeAndPlatformCodeAndCountryCodeOrderByScoreDescLastSeenAtDesc(
            String categoryCode,
            String platformCode,
            String countryCode
    );
}
