package com.dalai.llama.postprod.kafka;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Films are joined one at a time, and the container's memory budget depends on it.
 *
 * <p>Every tenant's request goes onto one topic and is consumed serially. The 2Gi limit in the chart
 * is sized for ONE ffmpeg: a single filter_complex holds a decoder and frame buffers open for every
 * shot at once, and ffmpeg is a separate process counted against the same container limit. Two joins
 * sharing that budget does not make one of them slow -- the OOM killer takes the pod and every
 * in-flight film dies together.
 *
 * <p>Asserted against the config file because this is a property of deployment, not of code, and it
 * was previously true only by virtue of a framework default nobody had written down. A reasonable
 * person tuning throughput would raise it without knowing what it was holding up.
 */
class FilmJoinSerialisationTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> applicationYml() throws Exception {
        try (InputStream in = FilmJoinSerialisationTest.class.getResourceAsStream("/application.yml")) {
            assertNotNull(in, "application.yml must be on the test classpath");
            return new Yaml().loadAll(in).iterator().next() instanceof Map<?, ?> map
                    ? (Map<String, Object>) map : Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static Object at(Map<String, Object> root, String... path) {
        Object node = root;
        for (String key : path) {
            if (!(node instanceof Map)) {
                return null;
            }
            node = ((Map<String, Object>) node).get(key);
        }
        return node;
    }

    @Test
    void onlyOneFilmIsJoinedAtATime() throws Exception {
        Map<String, Object> yml = applicationYml();

        assertEquals(1, at(yml, "spring", "kafka", "listener", "concurrency"),
                "more than one listener thread puts two ffmpeg runs in one container's memory budget");
    }

    @Test
    void aJoinIsOneRecordAndIsGivenTimeToFinish() throws Exception {
        Map<String, Object> yml = applicationYml();
        Map<String, Object> props =
                (Map<String, Object>) at(yml, "spring", "kafka", "consumer", "properties");
        assertNotNull(props, "consumer properties must be present");

        assertEquals(1, props.get("max.poll.records"),
                "these are ffmpeg runs, not messages -- one per poll");

        // A join holds its record for as long as ffmpeg takes. On the 5-minute default the consumer
        // is declared dead mid-join, the group rebalances, and the same film is joined twice.
        String pollInterval = String.valueOf(props.get("max.poll.interval.ms"));
        assertTrue(pollInterval.contains("1800000"),
                "max.poll.interval.ms must outlast a join, was: " + pollInterval);
    }
}
