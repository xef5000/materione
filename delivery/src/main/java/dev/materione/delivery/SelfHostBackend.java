package dev.materione.delivery;

import com.sun.net.httpserver.HttpExchange;
import dev.materione.core.delivery.PackDeliveryBackend;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import team.unnamed.creative.server.ResourcePackServer;
import team.unnamed.creative.server.handler.ResourcePackRequestHandler;

/**
 * The self-host HTTP backend (master §8.4, M1 scope): a read-only file
 * server over the compiled pack, separate from any future editor listener
 * and its higher trust level (master §9.2). Versioned URLs
 * ({@code /pack-<hash12>/pack.zip}) are the cache-buster: publishing a new
 * pack serves a new path, while the previous path keeps serving its bytes
 * so players mid-download never fetch a truncated mix.
 */
public final class SelfHostBackend implements PackDeliveryBackend {

    /** How many recent pack versions stay downloadable (in-flight clients). */
    private static final int RETAINED_VERSIONS = 4;

    private final ResourcePackServer server;
    private final String publicBase;
    /** path → pack bytes, insertion-ordered; the current version plus predecessors. */
    private final Map<String, byte[]> served = new LinkedHashMap<>();
    private final Set<String> pinned = new HashSet<>();
    private volatile String currentPath = "";

    public SelfHostBackend(String bindHost, int port, String publicHost) throws IOException {
        this.server = ResourcePackServer.server()
                .address(bindHost, port)
                .handler(new VersionedHandler())
                .path("/")
                .build();
        this.server.start();
        // port 0 = ephemeral: the bound port is real only after build
        this.publicBase = "http://" + publicHost + ":" + server.address().getPort();
    }

    @Override
    public synchronized String publish(Path packZip, String sha256) throws IOException {
        byte[] bytes = Files.readAllBytes(packZip);
        String path = "/pack-" + sha256.substring(0, Math.min(12, sha256.length())) + "/pack.zip";
        served.put(path, bytes);
        trim();
        currentPath = path;
        return publicBase + path;
    }

    @Override
    public synchronized void pin(String url) {
        pinned.add(pathOf(url));
    }

    @Override
    public synchronized void unpin(String url) {
        pinned.remove(pathOf(url));
        trim();
    }

    private void trim() {
        while (served.size() > RETAINED_VERSIONS) {
            Iterator<Map.Entry<String, byte[]>> entries = served.entrySet().iterator();
            boolean removed = false;
            while (entries.hasNext()) {
                Map.Entry<String, byte[]> entry = entries.next();
                if (!pinned.contains(entry.getKey())) {
                    entries.remove();
                    removed = true;
                    break;
                }
            }
            if (!removed) {
                return;
            }
        }
    }

    private String pathOf(String url) {
        return url.startsWith(publicBase) ? url.substring(publicBase.length()) : url;
    }

    /** The current pack URL (empty before the first publish). */
    public String currentUrl() {
        return publicBase + currentPath;
    }

    public int servedVersions() {
        synchronized (this) {
            return served.size();
        }
    }

    @Override
    public void close() {
        server.stop(1);
    }

    /** The Minecraft client's pack hash (SHA-1) for {@code setResourcePack}. */
    public static String sha1Of(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }

    /**
     * Serves the versioned paths; unknown paths (and non-Minecraft clients,
     * per creative's request parsing) get 404. The pack file is public,
     * read-only data — the editor listener (M3+) is the authenticated one
     * (master §9.2).
     */
    private final class VersionedHandler implements ResourcePackRequestHandler {
        @Override
        public void onRequest(
                team.unnamed.creative.server.request.ResourcePackDownloadRequest request,
                HttpExchange exchange) throws IOException {
            byte[] pack;
            synchronized (SelfHostBackend.this) {
                pack = served.get(exchange.getRequestURI().getPath());
            }
            if (pack == null) {
                byte[] body = "not found".getBytes(StandardCharsets.US_ASCII);
                exchange.getResponseHeaders().set("Content-Type", "text/plain");
                exchange.sendResponseHeaders(404, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/zip");
            exchange.sendResponseHeaders(200, pack.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(pack);
            }
        }
    }
}
