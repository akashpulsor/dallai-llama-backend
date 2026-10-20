package com.dalai.llama.tenant.showcase.inspiration;

import com.dalai.llama.tenant.showcase.ShowcaseDbTestSupport;
import com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/** The Inspiration board on a real Postgres: each pin keeps the height its card is sized by. */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(InspirationStore.class)
class InspirationStoreDbTest extends ShowcaseDbTestSupport {

    @Autowired private InspirationStore store;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        wipe(jdbc);
    }

    @Test
    void aPinKeepsItsPlayerHeight_andRelabellingWithoutOneKeepsIt() {
        store.save("1039557526480701962", "Face wash ad", ShowcaseIndustry.BEAUTY, null, 0, 899);
        store.save("1113515076632302385", "Chocolate edit", ShowcaseIndustry.FOOD_BEVERAGE, null, 1, 352);
        store.save("1039557526480701962", "Face wash ad with motion graphics", ShowcaseIndustry.BEAUTY, null, 0, null);

        assertThat(store.active(null)).extracting(InspirationItem::title, InspirationItem::embedHeight)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Face wash ad with motion graphics", 899),
                        org.assertj.core.groups.Tuple.tuple("Chocolate edit", 352));
    }

    @Test
    void anItemGetsItsPictureVideoAndMaker_thenNoLongerNeedsThem() {
        InspirationItem juice = store.save("1037376095420175260", "Healthy juice reel", ShowcaseIndustry.FOOD_BEVERAGE, null, 0, 899);
        assertThat(store.withoutMedia(10)).extracting(InspirationItem::id).containsExactly(juice.id());

        InspirationPosters posters = org.mockito.Mockito.mock(InspirationPosters.class);
        InspirationMediaJob job = new InspirationMediaJob(store, posters);
        org.mockito.Mockito.when(posters.fetch("1037376095420175260")).thenReturn(java.util.Optional.of(new InspirationPosters.Poster(
                "https://i.pinimg.com/564x/juice.jpg", 564, 1002, "Dadu Fresh", "https://v1.pinimg.com/videos/720p/juice.mp4",
                new java.math.BigDecimal("29.97"))));
        assertThat(job.run()).isEqualTo(1);

        InspirationItem filled = store.active(ShowcaseIndustry.FOOD_BEVERAGE).get(0);
        assertThat(filled.posterUrl()).isEqualTo("https://i.pinimg.com/564x/juice.jpg");
        assertThat(filled.videoUrl()).isEqualTo("https://v1.pinimg.com/videos/720p/juice.mp4");
        assertThat(filled.durationSeconds()).isEqualByComparingTo("29.97");
        assertThat(filled.makerName()).isEqualTo("Dadu Fresh");
        assertThat(store.withoutMedia(10)).isEmpty();
    }
}
