package dev.materione.testing.relay;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A contract-accurate in-memory stand-in for the deployed editor relay
 * worker: the same endpoints, shapes, and error codes pinned by
 * {@code fixtures/protocol/relay-mailbox-v1.json}. Serves on loopback with
 * a virtual-thread executor (long-polls must not serialize behind each
 * other). Lives in {@code testing} so JVM tests — the web module's
 * RelaySession test today, the Paper harness's relay gate next — can drive
 * the real {@code RelayClient}/{@code RelaySession} without the cloud.
 */
public final class FakeMailbox {

    /** Capabilities as the worker mints them. */
    public record Channel(String pluginToken, String editorToken, boolean closed) {}

    private record Stored(String body, long expires) {}

    private final Map<String, Channel> channels = new ConcurrentHashMap<>();
    private final Map<String, List<String>> pluginToEditor = new ConcurrentHashMap<>();
    private final Map<String, List<String>> editorToPlugin = new ConcurrentHashMap<>();
    private final Map<String, Stored> blobs = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();
    private HttpServer server;

    public HttpServer start() throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        Executor executor = Executors.newVirtualThreadPerTaskExecutor();
        http.setExecutor(executor);
        http.createContext("/v1/channels", this::handleChannels);
        http.createContext("/v1/store", this::handleStore);
        http.start();
        this.server = http;
        return http;
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ------------------------------------------------------------------

    private void handleChannels(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        try {
            if (path.equals("/v1/channels") && "POST".equals(exchange.getRequestMethod())) {
                String id = "chan-" + ids.incrementAndGet();
                String pluginToken = "plugin-token-" + ids.incrementAndGet() + "-0123456789abcdef";
                String editorToken = "editor-token-" + ids.incrementAndGet() + "-0123456789abcdef";
                channels.put(id, new Channel(pluginToken, editorToken, false));
                synchronized (pluginToEditor) {
                    pluginToEditor.put(id, new ArrayList<>());
                }
                synchronized (editorToPlugin) {
                    editorToPlugin.put(id, new ArrayList<>());
                }
                JsonObject out = new JsonObject();
                out.addProperty("channelId", id);
                out.addProperty("pluginToken", pluginToken);
                out.addProperty("editorToken", editorToken);
                out.addProperty("expires", System.currentTimeMillis() / 1000 + 43_200);
                out.addProperty("ttlSeconds", 43_200);
                respond(exchange, 201, out.toString());
                return;
            }
            String channelId = path.substring("/v1/channels/".length());
            channelId = channelId.contains("/") ? channelId.split("/")[0] : channelId;
            Channel channel = channels.get(channelId);
            String token = bearer(exchange);
            if (channel == null) {
                respond(exchange, 404, "{\"error\":\"no_such_channel\"}");
                return;
            }
            boolean pluginSide = token != null && token.equals(channel.pluginToken());
            boolean editorSide = token != null && token.equals(channel.editorToken());
            if (!pluginSide && !editorSide) {
                respond(exchange, 403, "{\"error\":\"invalid_token\"}");
                return;
            }
            if (path.endsWith("/close") && "POST".equals(exchange.getRequestMethod())) {
                channels.put(channelId, new Channel(channel.pluginToken(), channel.editorToken(), true));
                respond(exchange, 200, "{\"closed\":true}");
                return;
            }
            if (path.endsWith("/frames") && "POST".equals(exchange.getRequestMethod())) {
                JsonObject parsed = JsonParser.parseString(body).getAsJsonObject();
                List<String> queue = pluginSide ? pluginToEditor(channelId) : editorToPlugin(channelId);
                synchronized (queue) {
                    for (var element : parsed.getAsJsonArray("frames")) {
                        queue.add(element.toString());
                    }
                    queue.notifyAll();
                }
                respond(exchange, 202, "{\"accepted\":" + parsed.getAsJsonArray("frames").size() + "}");
                return;
            }
            if (path.endsWith("/frames") && "GET".equals(exchange.getRequestMethod())) {
                // The reader reads the OPPOSITE direction (fixture direction map).
                List<String> mine = pluginSide ? editorToPlugin(channelId) : pluginToEditor(channelId);
                long after = 0;
                int wait = 0;
                for (String pair : exchange.getRequestURI().getQuery().split("&")) {
                    if (pair.startsWith("after=")) {
                        after = Long.parseLong(pair.substring(6));
                    }
                    if (pair.startsWith("wait=")) {
                        wait = Integer.parseInt(pair.substring(5));
                    }
                }
                long deadline = System.currentTimeMillis() + wait * 1000L;
                synchronized (mine) {
                    while (mine.size() <= after
                            && !channels.get(channelId).closed()
                            && System.currentTimeMillis() < deadline) {
                        TimeUnit.MILLISECONDS.timedWait(mine, 100);
                    }
                }
                JsonObject out = new JsonObject();
                out.addProperty("closed", channels.get(channelId).closed());
                var frames = new JsonArray();
                synchronized (mine) {
                    for (long i = after; i < mine.size(); i++) {
                        JsonObject delivered = new JsonObject();
                        delivered.addProperty("id", i + 1);
                        delivered.add("frame", JsonParser.parseString(mine.get((int) i)).getAsJsonObject());
                        frames.add(delivered);
                    }
                }
                out.add("frames", frames);
                out.addProperty("oldest", 1);
                out.addProperty("more", false);
                respond(exchange, 200, out.toString());
                return;
            }
            respond(exchange, 405, "{\"error\":\"method_not_allowed\"}");
        } catch (InterruptedException e) {
            respond(exchange, 500, "{\"error\":\"interrupted\"}");
        } catch (RuntimeException e) {
            respond(exchange, 400, "{\"error\":\"bad_request\"}");
        }
    }

    private List<String> pluginToEditor(String channelId) {
        return queue(pluginToEditor, channelId);
    }

    private List<String> editorToPlugin(String channelId) {
        return queue(editorToPlugin, channelId);
    }

    private static List<String> queue(Map<String, List<String>> map, String channelId) {
        List<String> queue = map.get(channelId);
        if (queue == null) {
            throw new IllegalStateException("channel queues missing for " + channelId);
        }
        return queue;
    }

    private void handleStore(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/v1/store") && "POST".equals(exchange.getRequestMethod())) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonParser.parseString(body); // must be JSON (the real worker checks the envelope shape)
            String requested = exchange.getRequestURI().getQuery();
            String wanted = Arrays.stream(requested == null ? new String[0] : requested.split("&"))
                    .filter(pair -> pair.startsWith("code="))
                    .map(pair -> pair.substring("code=".length()))
                    .findFirst()
                    .orElse(null);
            String code = wanted != null ? wanted : "store-" + ids.incrementAndGet();
            if (blobs.containsKey(code)) {
                JsonObject deduped = new JsonObject();
                deduped.addProperty("code", code);
                deduped.addProperty("deduped", true);
                deduped.addProperty("expires", System.currentTimeMillis() / 1000 + 3600);
                respond(exchange, 200, deduped.toString());
                return;
            }
            blobs.put(code, new Stored(body, System.currentTimeMillis() / 1000 + 3600));
            JsonObject out = new JsonObject();
            out.addProperty("code", code);
            out.addProperty("deleteToken", "delete-" + ids.incrementAndGet());
            out.addProperty("expires", System.currentTimeMillis() / 1000 + 3600);
            respond(exchange, 201, out.toString());
            return;
        }
        String code = path.substring("/v1/store/".length());
        Stored stored = blobs.get(code);
        if (stored == null) {
            respond(exchange, 404, "{\"error\":\"no_such_blob\"}");
            return;
        }
        respond(exchange, 200, stored.body());
    }

    private static String bearer(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("authorization");
        return header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("content-type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
