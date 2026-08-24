package dev.materione.platform.probe;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * version.json extraction: both historical shapes of {@code pack_version}
 * parse, and anything malformed degrades to empty (detection is
 * best-effort; the pack-format table is the fallback).
 */
class ServerPackFormatTest {

    @Test
    void readsTheSplitObjectShape() {
        // the actual 1.21.4 server jar document (data/resource split)
        String json = "{\"id\":\"1.21.4\",\"world_version\":4189,"
                + "\"pack_version\":{\"resource\":46,\"data\":61},\"stable\":true}";
        assertThat(ServerPackFormat.parseResourceFormat(json)).contains(46);
    }

    @Test
    void readsThePlainIntShape() {
        assertThat(ServerPackFormat.parseResourceFormat("{\"pack_version\":64}")).contains(64);
    }

    @Test
    void malformedDocumentsDegradeToEmpty() {
        assertThat(ServerPackFormat.parseResourceFormat("{}")).isEmpty();
        assertThat(ServerPackFormat.parseResourceFormat("{\"pack_version\":{}}")).isEmpty();
        assertThat(ServerPackFormat.parseResourceFormat("{\"pack_version\":null}")).isEmpty();
        assertThat(ServerPackFormat.parseResourceFormat("not json")).isEmpty();
        assertThat(ServerPackFormat.parseResourceFormat("")).isEmpty();
        assertThat(ServerPackFormat.parseResourceFormat("{\"pack_version\":\"46\"}")).isEmpty();
    }
}
