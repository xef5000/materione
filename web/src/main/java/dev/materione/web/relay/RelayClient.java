package dev.materione.web.relay;

import com.google.gson.JsonObject;
import dev.materione.web.protocol.Wire;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * HTTP client for the relay worker's /v1 API (web.md §3): channel create,
 * opaque message post, long-poll fetch, close, and the payload store's blob
 * put/get. Speaks exactly the shapes pinned by
 * {@code fixtures/protocol/relay-mailbox-v1.json}.
 *
 * <p>Blocking by design — the {@link RelaySession} pump parks it on a host
 * async thread (R5: {@code web/} never spawns its own threads).
 */
public final class RelayClient {

    /** Capabilities returned by channel creation. */
    public record Channel(String channelId, String pluginToken, String editorToken, long expires) {}

    /** One delivered message: the worker-assigned id and the opaque JSON text. */
    public record Delivered(long id, String messageText) {}

    /** A poll outcome: either closed, or messages past the cursor (ordered). */
    public record Poll(boolean closed, List<Delivered> messages, Long oldest, boolean more) {}

    /** A stored blob's upload receipt. */
    public record Stored(String code, String deleteToken, long expires, boolean deduped) {}

    private final URI base;
    private final HttpClient http;

    public RelayClient(URI baseUrl) {
        String text = baseUrl.toString();
        this.base = URI.create(text.endsWith("/") ? text.substring(0, text.length() - 1) : text);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public Channel createChannel() throws RelayException {
        JsonObject body = postJson("/v1/channels", "", "", 201);
        return new Channel(
                Wire.requiredString(body, "channelId"),
                Wire.requiredString(body, "pluginToken"),
                Wire.requiredString(body, "editorToken"),
                body.has("expires") ? body.get("expires").getAsLong() : 0L);
    }

    /** Posts opaque channel messages (handshake JSON or sealed frames). */
    public void postMessages(String channelId, String token, List<String> messageTexts)
            throws RelayException {
        JsonObject body = new JsonObject();
        var frames = new com.google.gson.JsonArray();
        for (String messageText : messageTexts) {
            frames.add(Wire.parseObject(messageText));
        }
        body.add("frames", frames);
        postJson("/v1/channels/" + channelId + "/frames", token, Wire.canonicalJson(body), 202);
    }

    /** Long-poll; blocks up to {@code waitSeconds} when nothing is queued. */
    public Poll poll(String channelId, String token, long after, int waitSeconds)
            throws RelayException {
        try {
            return pollAsync(channelId, token, after, waitSeconds)
                    .get(waitSeconds + 15, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RelayException relay) {
                throw relay;
            }
            throw new RelayException("poll failed: " + cause, 0, null, cause);
        } catch (InterruptedException e) {
            // R5: thread management is the host gateway's business; the loop
            // exits on its next boundary instead of re-flagging here.
            throw new RelayException("interrupted", 0, null, e);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new RelayException("poll timed out", 0, null, e);
        }
    }

    /**
     * Cancellable long-poll — the exact request {@link #poll} makes, as a
     * future: cancelling it aborts the in-flight HTTP wait, so the session
     * pump can flush a queued outbound frame immediately instead of staying
     * parked until the wait expires (server-pushed events keep their
     * sub-second latency rather than lagging up to one poll window).
     */
    public CompletableFuture<Poll> pollAsync(String channelId, String token, long after, int waitSeconds) {
        URI uri = base.resolve("/v1/channels/" + channelId + "/frames?after=" + after
                + "&wait=" + waitSeconds);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(waitSeconds + 15))
                .GET()
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        throw new java.util.concurrent.CompletionException(relayError(response));
                    }
                    return parsePoll(Wire.parseObject(response.body()));
                });
    }

    private static Poll parsePoll(JsonObject body) {
        boolean closed = body.has("closed") && body.get("closed").getAsBoolean();
        var out = new java.util.ArrayList<Delivered>();
        if (body.has("frames") && body.get("frames").isJsonArray()) {
            for (var element : body.getAsJsonArray("frames")) {
                JsonObject delivered = element.getAsJsonObject();
                out.add(new Delivered(
                        delivered.get("id").getAsLong(),
                        delivered.get("frame").toString()));
            }
        }
        Long oldest = null;
        if (body.has("oldest") && body.get("oldest").isJsonPrimitive()) {
            oldest = body.get("oldest").getAsLong();
        }
        boolean more = body.has("more") && body.get("more").getAsBoolean();
        return new Poll(closed, List.copyOf(out), oldest, more);
    }

    public void closeChannel(String channelId, String token) throws RelayException {
        HttpRequest request = HttpRequest.newBuilder(
                        base.resolve("/v1/channels/" + channelId + "/close"))
                .header("authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        send(request, 200);
    }

    /**
     * Stores one envelope blob. {@code requestedCode} is the content-addressed
     * id when the caller wants dedup; null asks the relay to mint one.
     */
    public Stored storePut(String envelopeJson, String requestedCode, int ttlSeconds)
            throws RelayException {
        StringBuilder query = new StringBuilder("/v1/store?ttl=").append(ttlSeconds);
        if (requestedCode != null) {
            query.append("&code=").append(requestedCode);
        }
        HttpRequest request = HttpRequest.newBuilder(base.resolve(query.toString()))
                .timeout(Duration.ofSeconds(30))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(envelopeJson, StandardCharsets.UTF_8))
                .build();
        JsonObject body = send(request, 200, 201);
        return new Stored(
                Wire.requiredString(body, "code"),
                body.has("deleteToken") ? body.get("deleteToken").getAsString() : "",
                body.has("expires") ? body.get("expires").getAsLong() : 0L,
                body.has("deduped") && body.get("deduped").getAsBoolean());
    }

    /** Fetches one envelope blob's body; null when the code is unknown/expired. */
    public String storeGet(String code) throws RelayException {
        HttpRequest request = HttpRequest.newBuilder(base.resolve("/v1/store/" + code))
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                return null;
            }
            if (response.statusCode() != 200) {
                throw relayError(response);
            }
            return response.body();
        } catch (IOException e) {
            throw new RelayException("relay unreachable: " + e.getMessage(), 0, null, e);
        } catch (InterruptedException e) {
            // R5: thread management is the host gateway's business; the loop
            // exits on its next boundary instead of re-flagging here.
            throw new RelayException("interrupted", 0, null, e);
        }
    }

    // ------------------------------------------------------------------

    private JsonObject postJson(String path, String bearer, String body, int expectedStatus)
            throws RelayException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(base.resolve(path))
                .timeout(Duration.ofSeconds(30))
                .header("content-type", "application/json");
        if (!bearer.isEmpty()) {
            builder.header("authorization", "Bearer " + bearer);
        }
        HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return send(request, expectedStatus);
    }

    private JsonObject send(HttpRequest request, int... expectedStatuses) throws RelayException {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            for (int expected : expectedStatuses) {
                if (response.statusCode() == expected) {
                    return Wire.parseObject(response.body());
                }
            }
            throw relayError(response);
        } catch (IOException e) {
            throw new RelayException("relay unreachable: " + e.getMessage(), 0, null, e);
        } catch (InterruptedException e) {
            // R5: thread management is the host gateway's business; the loop
            // exits on its next boundary instead of re-flagging here.
            throw new RelayException("interrupted", 0, null, e);
        }
    }

    private static RelayException relayError(HttpResponse<String> response) {
        String code = null;
        try {
            JsonObject body = Wire.parseObject(response.body());
            if (body.has("error")) {
                code = body.get("error").getAsString();
            }
        } catch (Exception ignored) {
            // non-JSON error body (edge 5xx etc.) — status is enough
        }
        return new RelayException(
                "relay returned " + response.statusCode() + (code == null ? "" : " (" + code + ")"),
                response.statusCode(), code, null);
    }
}
