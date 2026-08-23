package dev.materione.core.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Fingerprint persistence and drift detection (the targeted-reload correctness net). */
class FingerprintCacheTest {

    @TempDir
    Path dir;

    @Test
    void saveLoadRoundTrip() throws Exception {
        Path packs = dir.resolve("packs");
        Files.createDirectories(packs.resolve("sample").resolve("items"));
        Files.writeString(packs.resolve("sample").resolve("items").resolve("a.yml"), "a: 1\n");
        Files.writeString(packs.resolve("sample").resolve("pack.yml"), "id: sample\n");

        FingerprintCache cache = FingerprintCache.empty();
        cache.record("sample/items/a.yml", FingerprintCache.sha256(
                packs.resolve("sample").resolve("items").resolve("a.yml")));
        assertThat(cache.save(dir.resolve("fingerprints.bin"))).isTrue();

        FingerprintCache loaded = FingerprintCache.load(dir.resolve("fingerprints.bin"));
        assertThat(loaded.fingerprintOf("sample/items/a.yml"))
                .isEqualTo(FingerprintCache.sha256(
                        packs.resolve("sample").resolve("items").resolve("a.yml")));
    }

    @Test
    void tornFileReadsAsAbsent() throws Exception {
        Path file = dir.resolve("fingerprints.bin");
        Files.writeString(file, "not the binary format");
        assertThat(FingerprintCache.load(file).fingerprintOf("anything")).isNull();
    }

    @Test
    void missingFileHasNoFingerprint() {
        assertThat(FingerprintCache.empty().fingerprintOf("x")).isNull();
    }
}
