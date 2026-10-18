package com.dalai.llama.tenant.showcase.inspiration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Reads the picture and maker out of Pinterest's pin info, the shape its widgets endpoint answers with. */
class InspirationPostersTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void takesThe564pxPictureAndTheMaker() throws Exception {
        var pin = json.readTree("""
                {"images":{"237x":{"width":236,"height":419,"url":"https://i.pinimg.com/236x/a.jpg"},
                           "564x":{"width":564,"height":1002,"url":"https://i.pinimg.com/564x/a.jpg"}},
                 "native_creator":{"full_name":"Dadu Fresh "},"pinner":{"full_name":"Someone"}}""");

        assertThat(InspirationPosters.poster(pin)).contains(
                new InspirationPosters.Poster("https://i.pinimg.com/564x/a.jpg", 564, 1002, "Dadu Fresh"));
    }

    @Test
    void fallsBackToTheSmallPictureAndThePinner_andNeedsAPicture() throws Exception {
        var small = json.readTree("""
                {"images":{"237x":{"width":236,"height":419,"url":"https://i.pinimg.com/236x/a.jpg"}},"pinner":{"full_name":"Someone"}}""");
        assertThat(InspirationPosters.poster(small)).hasValueSatisfying(p -> {
            assertThat(p.url()).isEqualTo("https://i.pinimg.com/236x/a.jpg");
            assertThat(p.makerName()).isEqualTo("Someone");
        });
        assertThat(InspirationPosters.poster(json.readTree("{\"images\":{}}"))).isEmpty();
    }
}
