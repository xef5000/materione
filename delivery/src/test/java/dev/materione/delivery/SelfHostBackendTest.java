package dev.materione.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Self-host delivery behavior: versioned URLs, cache-busting, retention. */
class SelfHostBackendTest {

    @Test
    void publishesAndServesVersionedUrls(@TempDir Path tempDir) throws IOException {
        Path zip = tempDir.resolve("pack.zip");
        byte[] bytes = new byte[] {1, 2, 3, 4, 5};
        Files.write(zip, bytes);
        String sha256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

        try (SelfHostBackend backend = new SelfHostBackend("127.0.0.1", 0, "127.0.0.1")) {
            String url = backend.publish(zip, sha256);
            assertThat(url).startsWith("http://127.0.0.1:").contains("/pack-aaaaaaaaaaaa/pack.zip");

            byte[] fetched = httpGet(url);
            assertThat(fetched).isEqualTo(bytes);

            // old URL gone when a different hash is published? No — retained for
            // in-flight downloads; a NEW hash gets a NEW path (cache-busting)
            String secondUrl = backend.publish(zip, "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
            assertThat(secondUrl).isNotEqualTo(url);
            assertThat(httpGet(url)).isEqualTo(bytes); // predecessor still served
            assertThat(backend.servedVersions()).isEqualTo(2);
        }
    }

    @Test
    void unknownPathIs404(@TempDir Path tempDir) throws IOException {
        Path zip = tempDir.resolve("pack.zip");
        Files.write(zip, new byte[] {9});
        try (SelfHostBackend backend = new SelfHostBackend("127.0.0.1", 0, "127.0.0.1")) {
            String url = backend.publish(zip, "cccccccccccccccccccccccccccccccccccccccc");
            int port = portOf(url);
            HttpURLConnection connection = (HttpURLConnection)
                    java.net.URI.create("http://127.0.0.1:" + port + "/pack-dddddddddddd/pack.zip").toURL().openConnection();
            assertThat(connection.getResponseCode()).isEqualTo(404);
        }
    }

    private static byte[] httpGet(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) java.net.URI.create(url).toURL().openConnection();
        assertThat(connection.getResponseCode()).isEqualTo(200);
        assertThat(connection.getContentType()).isEqualTo("application/zip");
        return connection.getInputStream().readAllBytes();
    }

    private static int portOf(String url) {
        // http://host:port/path
        int colon = url.indexOf(':', "http://".length());
        return Integer.parseInt(url.substring(colon + 1, url.indexOf('/', colon)));
    }
}
