package dev.materione.web.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The blob's self-description (web.md §4.1/§4.2): protocol versions and
 * capabilities round-trip, and a pre-capabilities blob (an older jar's
 * output) parses with an empty list — the <em>panel</em> decides what an
 * empty list means, the parser stays neutral.
 */
class SessionBlobTest {

    @Test
    void capabilitiesRoundTripThroughBytes() {
        SessionBlob blob = new SessionBlob(
                SessionBlob.generateSessionId(new java.security.SecureRandom()),
                List.of(1, 2),
                List.of("glyphs", "items"),
                new byte[32],
                new SessionBlob.RelayInfo("https://editor.example", "ch", "tok"),
                null);
        SessionBlob parsed = SessionBlob.fromBodyBytes(blob.bodyBytes());
        assertThat(parsed.capabilities()).containsExactly("glyphs", "items");
        assertThat(parsed.protocolVersions()).containsExactly(1, 2);
        assertThat(parsed.relay()).isNotNull();
    }

    @Test
    void legacyBlobWithoutCapabilitiesParsesEmpty() {
        // Shape an old jar emitted before the field existed.
        JsonObject legacy = new JsonObject();
        legacy.addProperty("sessionId", SessionBlob.generateSessionId(new java.security.SecureRandom()));
        var versions = new com.google.gson.JsonArray();
        versions.add(1);
        legacy.add("protocolVersions", versions);
        Wire.putBinary(legacy, "pluginSigningPubKey", new byte[32]);

        SessionBlob parsed = SessionBlob.fromBodyBytes(Wire.canonicalBytes(legacy));
        assertThat(parsed.capabilities()).isEmpty();
        // Re-serialization keeps the shape closed: empty list, not a missing key.
        assertThat(parsed.toJson().get("capabilities").isJsonArray()).isTrue();
    }

    @Test
    void currentCapabilitiesAreSortedDistinctNames() {
        // The list rides into the golden fixture byte-for-byte and the panel
        // mirrors it — order is part of the contract.
        assertThat(SessionBlob.CURRENT_CAPABILITIES)
                .isSorted()
                .doesNotHaveDuplicates()
                .allSatisfy(name -> assertThat(name).matches("[a-z][a-z0-9-]*"));
    }
}
