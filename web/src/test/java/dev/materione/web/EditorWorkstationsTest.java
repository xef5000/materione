package dev.materione.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Trusted-workstation store (web.md §5.2): trust, recognize, revoke, persist. */
class EditorWorkstationsTest {

    @TempDir
    Path temp;

    @Test
    void trustRecognizeAndRevokeByPrefix() throws Exception {
        Path file = temp.resolve("storage/editor-workstations.json");
        EditorWorkstations store = new EditorWorkstations(file);

        byte[] browserKey = new byte[32];
        new SecureRandom().nextBytes(browserKey);
        byte[] strangerKey = new byte[32];
        new SecureRandom().nextBytes(strangerKey);

        assertThat(store.isTrusted(browserKey)).isFalse();
        store.trust(browserKey, "Chrome on the admin PC");
        assertThat(store.isTrusted(browserKey)).isTrue();
        assertThat(store.isTrusted(strangerKey)).isFalse();

        List<EditorWorkstations.Entry> entries = store.list();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).label()).isEqualTo("Chrome on the admin PC");
        assertThat(entries.get(0).keyHash()).isEqualTo(EditorWorkstations.keyHash(browserKey));

        // Revoke by the hash prefix the in-game command shows.
        String prefix = entries.get(0).keyHash().substring(0, 8);
        assertThat(store.revoke(prefix)).isTrue();
        assertThat(store.isTrusted(browserKey)).isFalse();
        assertThat(store.revoke(prefix)).isFalse();
    }

    @Test
    void persistsAcrossReloads() {
        Path file = temp.resolve("storage/editor-workstations.json");
        byte[] browserKey = new byte[32];
        new SecureRandom().nextBytes(browserKey);

        new EditorWorkstations(file).trust(browserKey, "Firefox");
        assertThat(Files.exists(file)).isTrue();

        EditorWorkstations reloaded = new EditorWorkstations(file);
        assertThat(reloaded.isTrusted(browserKey)).isTrue();
        assertThat(reloaded.list().get(0).label()).isEqualTo("Firefox");

        reloaded.revokeAll();
        assertThat(new EditorWorkstations(file).isTrusted(browserKey)).isFalse();
    }

    @Test
    void missingFileStartsEmpty() {
        EditorWorkstations store = new EditorWorkstations(temp.resolve("storage/none.json"));
        assertThat(store.list()).isEmpty();
        assertThat(store.revoke("abcdef01")).isFalse();
    }
}
