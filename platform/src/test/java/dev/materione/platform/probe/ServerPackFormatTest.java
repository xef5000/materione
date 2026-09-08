package dev.materione.platform.probe;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * version.json extraction: all three historical shapes of {@code
 * pack_version} parse, and anything malformed degrades to empty (detection
 * is best-effort; the pack-format table is the fallback).
 */
class ServerPackFormatTest {

    private static final String V26 =
            "{\"id\":\"26.1.2\",\"world_version\":4790,"
                    + "\"pack_version\":{\"resource_major\":84,\"resource_minor\":0,"
                    + "\"data_major\":101,\"data_minor\":1},\"stable\":true}";

    @Test
    void readsThe26xMajorMinorShape() {
        var formats = ServerPackFormat.parse(V26).orElseThrow();
        assertThat(formats.resourceMajor()).isEqualTo(84);
        assertThat(formats.resourceMinor()).isEqualTo(0);
        assertThat(formats.dataMajor()).isEqualTo(101);
        assertThat(formats.dataMinor()).isEqualTo(1);
    }

    @Test
    void readsTheSplitObjectShape() {
        // the 1.21.4 server jar document (data/resource split)
        String json = "{\"id\":\"1.21.4\",\"world_version\":4189,"
                + "\"pack_version\":{\"resource\":46,\"data\":61},\"stable\":true}";
        var formats = ServerPackFormat.parse(json).orElseThrow();
        assertThat(formats.resourceMajor()).isEqualTo(46);
        assertThat(formats.resourceMinor()).isEqualTo(0);
        assertThat(formats.dataMajor()).isEqualTo(61);
        assertThat(formats.dataMinor()).isEqualTo(0);
    }

    @Test
    void readsThePlainIntShape() {
        var formats = ServerPackFormat.parse("{\"pack_version\":64}").orElseThrow();
        assertThat(formats.resourceMajor()).isEqualTo(64);
        // the plain int named the resource format only — the data half predates it
        assertThat(formats.dataMajor()).isEqualTo(0);
    }

    @Test
    void malformedDocumentsDegradeToEmpty() {
        assertThat(ServerPackFormat.parse("{}")).isEmpty();
        assertThat(ServerPackFormat.parse("{\"pack_version\":{}}")).isEmpty();
        assertThat(ServerPackFormat.parse("{\"pack_version\":null}")).isEmpty();
        assertThat(ServerPackFormat.parse("not json")).isEmpty();
        assertThat(ServerPackFormat.parse("")).isEmpty();
        assertThat(ServerPackFormat.parse("{\"pack_version\":\"46\"}")).isEmpty();
    }
}
