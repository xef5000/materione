package dev.materione.web.rpc.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.logic.trace.TraceBatch;
import dev.materione.api.logic.trace.TraceEntry;
import dev.materione.api.logic.trace.TraceKind;
import dev.materione.api.logic.trace.TraceLimits;
import dev.materione.behavior.flow.StepPath;
import dev.materione.behavior.run.TraceEvent;
import dev.materione.behavior.run.trace.TraceHub;
import dev.materione.testing.golden.GoldenFiles;
import dev.materione.web.protocol.Wire;
import dev.materione.web.rpc.RpcDispatcher;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * The live trace ops (logic.md §7.6, §32) over the real {@link TraceHub}: a subscription is scoped to the calling
 * session's player and the one GUI, the stream reaches only that session, the caps and the lease close what they
 * should, and the frames they exchange are pinned in {@code fixtures/protocol/editor-logic-trace-v1.json}. The SPA's
 * wrappers must produce the request frames byte for byte. Regenerate with {@code -Dmaterione.golden.regen=true}.
 */
class LogicTraceOpsTest {

    private static final String FILE_NAME = "editor-logic-trace-v1.json";
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000a11c");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-0000000000b0");
    private static final String SHOP = "demo:shop";

    /** An editor session as the op sees it: alive or not, and the frames written to it. */
    private static final class FakeChannel implements LogicTraceOps.Channel {
        boolean open = true;
        final List<JsonObject> frames = new ArrayList<>();

        @Override
        public boolean open() {
            return open;
        }

        @Override
        public void send(byte[] body) {
            frames.add(Wire.parseObject(new String(body, StandardCharsets.UTF_8)));
        }
    }

    private final AtomicLong clock = new AtomicLong(1_000_000);
    private final Map<String, FakeChannel> channels = new HashMap<>();
    private final List<Boolean> activity = new ArrayList<>();
    private Optional<UUID> player = Optional.of(ALICE);
    private TraceHub hub = new TraceHub(TraceLimits.DEFAULT, clock::get, activity::add);
    private RpcDispatcher dispatcher = dispatcher();

    private RpcDispatcher dispatcher() {
        var made = new RpcDispatcher();
        LogicTraceOps.register(made, hub, channels::get, () -> player, clock::get);
        return made;
    }

    private FakeChannel session(String id) {
        return channels.computeIfAbsent(id, key -> new FakeChannel());
    }

    private Wire.Response call(String session, String op, String id, Object... keyValues) {
        var body = new JsonObject();
        for (int i = 0; i < keyValues.length; i += 2) {
            Object value = keyValues[i + 1];
            if (value instanceof Boolean flag) {
                body.addProperty((String) keyValues[i], flag);
            } else {
                body.addProperty((String) keyValues[i], String.valueOf(value));
            }
        }
        return dispatcher.dispatch(new Wire.Request(op, id, body), session);
    }

    private Wire.Response subscribe(String session, String resource) {
        return call(session, "logic.trace.subscribe", "t-" + session + resource, "resource", resource);
    }

    private static TraceEvent event(long run, String resource, UUID who, TraceEvent.Type type, String path, String detail) {
        return new TraceEvent(run, resource, "demo:shop#0", who.toString(), StepPath.of(path.split("\\.")), type, detail);
    }

    private void fire(TraceEvent event) {
        hub.accept(event);
    }

    // --- subscribing -----------------------------------------------------------------

    @Test
    void subscribingStreamsOnlyThatPlayersRunsOfThatGuiToThatSession() {
        FakeChannel mine = session("s1");
        FakeChannel other = session("s2");
        assertThat(subscribe("s1", SHOP).ok()).isTrue();
        assertThat(subscribe("s2", "demo:menu").ok()).isTrue();

        fire(event(1, SHOP, ALICE, TraceEvent.Type.STEP_OK, "behavior.0.do.0", ""));
        fire(event(2, SHOP, BOB, TraceEvent.Type.STEP_OK, "behavior.0.do.0", "")); // another player
        fire(event(3, "demo:menu", ALICE, TraceEvent.Type.STEP_OK, "behavior.0.do.0", "")); // another GUI
        hub.flush();

        assertThat(mine.frames).hasSize(1);
        JsonObject frame = mine.frames.get(0);
        assertThat(frame.get("op").getAsString()).isEqualTo("push.logic.trace");
        JsonObject payload = frame.getAsJsonObject("payload");
        assertThat(payload.get("resource").getAsString()).isEqualTo(SHOP);
        assertThat(payload.getAsJsonArray("entries")).hasSize(1);
        assertThat(payload.getAsJsonArray("entries").get(0).getAsJsonObject().get("path").getAsString())
                .isEqualTo("behavior.0.do.0");
        assertThat(other.frames).hasSize(1); // the menu run belongs to the other session's GUI, scoped to Alice too
        assertThat(other.frames.get(0).getAsJsonObject("payload").get("resource").getAsString()).isEqualTo("demo:menu");
    }

    @Test
    void allPlayersWidensTheScopeAndTheReplySaysWhichScopeIsLive() {
        session("s1");
        Wire.Response mine = subscribe("s1", SHOP);
        assertThat(mine.data().getAsJsonObject().get("scope").getAsString()).isEqualTo("player");

        Wire.Response everyone = call("s1", "logic.trace.subscribe", "t-all", "resource", SHOP, "allPlayers", true);
        assertThat(everyone.ok()).isTrue();
        assertThat(everyone.data().getAsJsonObject().get("scope").getAsString()).isEqualTo("all");
        assertThat(hub.subscriptions()).isEqualTo(1); // the narrower one was replaced

        fire(event(1, SHOP, BOB, TraceEvent.Type.STEP_OK, "behavior.0.do.0", ""));
        hub.flush();
        assertThat(session("s1").frames).hasSize(1);
    }

    @Test
    void aConsoleSessionWithNoPlayerTracesTheGuiForEveryone() {
        player = Optional.empty();
        session("s1");

        Wire.Response response = subscribe("s1", SHOP);

        assertThat(response.data().getAsJsonObject().get("scope").getAsString()).isEqualTo("all");
    }

    @Test
    void subscribingAgainRenewsTheLeaseWithoutOpeningASecondSubscription() {
        session("s1");
        subscribe("s1", SHOP);
        clock.addAndGet(200_000);
        subscribe("s1", SHOP);
        clock.addAndGet(200_000); // 400 s after the first call, 200 s after the renewal

        fire(event(1, SHOP, ALICE, TraceEvent.Type.STEP_OK, "behavior.0.do.0", ""));
        hub.flush();

        assertThat(hub.subscriptions()).isEqualTo(1);
        assertThat(session("s1").frames).hasSize(1);
    }

    @Test
    void aLapsedLeaseClosesTheSubscriptionAtItsNextDelivery() {
        session("s1");
        subscribe("s1", SHOP);
        clock.addAndGet((LogicTraceOps.LEASE_SECONDS + 1) * 1000L);

        fire(event(1, SHOP, ALICE, TraceEvent.Type.STEP_OK, "behavior.0.do.0", ""));
        hub.flush();

        assertThat(session("s1").frames).isEmpty();
        assertThat(hub.subscriptions()).isZero();
        assertThat(activity).containsExactly(true, false);
    }

    @Test
    void anEndedSessionFreesItsSubscriptionsWhenSomeoneNextSubscribes() {
        subscribe("s1", SHOP);
        session("s1").open = false;
        session("s2");

        assertThat(subscribe("s2", "demo:menu").ok()).isTrue();

        assertThat(hub.subscriptions()).isEqualTo(1);
    }

    @Test
    void aSessionThatEndedMidStreamStopsReceiving() {
        FakeChannel mine = session("s1");
        subscribe("s1", SHOP);
        mine.open = false;

        fire(event(1, SHOP, ALICE, TraceEvent.Type.STEP_OK, "behavior.0.do.0", ""));
        hub.flush();

        assertThat(mine.frames).isEmpty();
        assertThat(hub.subscriptions()).isZero();
    }

    @Test
    void aSessionTracesAtMostFourGuisAndTheServerEightSubscriptions() {
        session("s1");
        for (int i = 0; i < LogicTraceOps.MAX_RESOURCES_PER_SESSION; i++) {
            assertThat(subscribe("s1", "demo:g" + i).ok()).isTrue();
        }
        Wire.Response fifth = subscribe("s1", "demo:g9");
        assertThat(fifth.ok()).isFalse();
        assertThat(fifth.error().code()).isEqualTo(DiagnosticCodes.TRACE_REFUSED.code());

        for (int s = 2; s <= 3; s++) {
            session("s" + s);
            for (int i = 0; i < LogicTraceOps.MAX_RESOURCES_PER_SESSION; i++) {
                subscribe("s" + s, "demo:h" + s + i);
            }
        }
        Wire.Response overTheServerCap = subscribe("s3", "demo:more");
        assertThat(overTheServerCap.ok()).isFalse();
        assertThat(overTheServerCap.error().code()).isEqualTo(DiagnosticCodes.TRACE_REFUSED.code());
        assertThat(hub.subscriptions()).isEqualTo(TraceLimits.DEFAULT.maxSubscriptions());
    }

    @Test
    void unsubscribingStopsTheStreamAndAnOmittedResourceStopsAllOfThem() {
        FakeChannel mine = session("s1");
        subscribe("s1", SHOP);
        subscribe("s1", "demo:menu");

        Wire.Response one = call("s1", "logic.trace.unsubscribe", "u-1", "resource", SHOP);
        assertThat(one.data().getAsJsonObject().get("unsubscribed").getAsInt()).isEqualTo(1);
        assertThat(hub.subscriptions()).isEqualTo(1);
        fire(event(1, SHOP, ALICE, TraceEvent.Type.STEP_OK, "behavior.0.do.0", ""));
        hub.flush();
        assertThat(mine.frames).isEmpty();

        Wire.Response all = call("s1", "logic.trace.unsubscribe", "u-2");
        assertThat(all.data().getAsJsonObject().get("unsubscribed").getAsInt()).isEqualTo(1);
        assertThat(hub.subscriptions()).isZero();
        assertThat(call("s1", "logic.trace.unsubscribe", "u-3").data().getAsJsonObject().get("unsubscribed").getAsInt())
                .isZero();
    }

    @Test
    void aMalformedRequestIsABadRequestNotAFailure() {
        session("s1");
        assertThat(call("s1", "logic.trace.subscribe", "b-1").error().code()).isEqualTo("MTR-0008");
        assertThat(call("s1", "logic.trace.subscribe", "b-2", "resource", "no-colon").error().code())
                .isEqualTo("MTR-0008");
        assertThat(call("s1", "logic.trace.subscribe", "b-3", "resource", "x".repeat(300) + ":y").error().code())
                .isEqualTo("MTR-0008");
        assertThat(call("nobody", "logic.trace.subscribe", "b-4", "resource", SHOP).error().code())
                .isEqualTo("MTR-0008");
        assertThat(hub.subscriptions()).isZero();
    }

    // --- the frame ---------------------------------------------------------------------

    @Test
    void aDropRidesTheFrameAsACount() {
        var tight = new TraceHub(new TraceLimits(10, 1_000, 1_000, 128, 8, 160), clock::get, activity::add);
        hub = tight;
        dispatcher = dispatcher();
        FakeChannel mine = session("s1");
        subscribe("s1", SHOP);

        for (int i = 0; i < 25; i++) {
            fire(event(i, SHOP, ALICE, TraceEvent.Type.STEP_OK, "behavior.0.do.0", ""));
        }
        hub.flush();

        JsonObject payload = mine.frames.get(0).getAsJsonObject("payload");
        assertThat(payload.getAsJsonArray("entries")).hasSize(10);
        assertThat(payload.get("dropped").getAsInt()).isEqualTo(15);
    }

    @Test
    void anOversizedBatchIsTrimmedToTheFrameCapAndTheLossIsCounted() {
        var entries = new ArrayList<TraceEntry>();
        for (int i = 0; i < 128; i++) {
            entries.add(new TraceEntry(i, SHOP, "r".repeat(400), "", "behavior.0.do." + i + ".then.".repeat(30),
                    TraceKind.FAILED, "why ".repeat(100), 5));
        }

        JsonObject frame = LogicTraceOps.frame(SHOP, new TraceBatch(entries, 2), 9);

        assertThat(Wire.canonicalBytes(frame).length).isLessThanOrEqualTo(LogicTraceOps.MAX_FRAME_BYTES);
        JsonObject payload = frame.getAsJsonObject("payload");
        int kept = payload.getAsJsonArray("entries").size();
        assertThat(kept).isLessThan(128).isGreaterThan(0);
        assertThat(payload.get("dropped").getAsInt()).isEqualTo(2 + (128 - kept));
        // text is cut, not rejected
        assertThat(payload.getAsJsonArray("entries").get(0).getAsJsonObject().get("rule").getAsString())
                .hasSizeLessThanOrEqualTo(LogicTraceOps.MAX_TEXT);
    }

    // --- the golden frames -------------------------------------------------------------

    @Test
    void goldenFixtureIsStable() throws IOException {
        JsonObject built = build();
        Path target = GoldenFiles.fixturesRoot().resolve("protocol").resolve(FILE_NAME);
        if (GoldenFiles.regenEnabled()) {
            Files.createDirectories(target.getParent());
            Files.writeString(target, pretty(built), StandardCharsets.UTF_8);
            return;
        }
        assertThat(target).exists().withFailMessage(
                "missing " + target + " - regenerate once with -Dmaterione.golden.regen=true");
        assertThat(pretty(built)).isEqualTo(Files.readString(target, StandardCharsets.UTF_8));
    }

    /** Runs each frame through the real ops in a fixed order, and records the pairs and the one pushed frame. */
    private JsonObject build() {
        var root = new JsonObject();
        var meta = new JsonObject();
        meta.addProperty("file", FILE_NAME);
        meta.addProperty("name", "materione live trace op frames");
        meta.addProperty("version", 1);
        root.add("meta", meta);

        FakeChannel mine = session("s1");
        root.add("subscribe", pair(new Wire.Request("logic.trace.subscribe", "t-0", payload("resource", SHOP))));
        root.add("subscribeAll", pair(new Wire.Request("logic.trace.subscribe", "t-1", allPlayers())));
        root.add("subscribeRefused", pair(new Wire.Request("logic.trace.subscribe", "t-2", payload("resource", "bad"))));

        // a click: the rule starts, an action runs, one fails with its reason, a branch is taken, the run ends
        fire(event(11, SHOP, ALICE, TraceEvent.Type.RUN_STARTED, "behavior.0", ""));
        fire(event(11, SHOP, ALICE, TraceEvent.Type.STEP_OK, "behavior.0.do.0", ""));
        fire(event(11, SHOP, ALICE, TraceEvent.Type.BRANCH, "behavior.0.do.1", "then"));
        fire(event(11, SHOP, ALICE, TraceEvent.Type.STEP_FAILED, "behavior.0.do.1.then.0", "not enough money"));
        fire(event(11, SHOP, ALICE, TraceEvent.Type.STEP_SKIPPED, "behavior.0.do.2", "chance 0.5"));
        fire(event(11, SHOP, ALICE, TraceEvent.Type.RUN_ENDED, "behavior.0", "FINISHED"));
        hub.flush();
        root.add("push", mine.frames.get(mine.frames.size() - 1));

        root.add("unsubscribe", pair(new Wire.Request("logic.trace.unsubscribe", "t-3", payload("resource", SHOP))));
        return root;
    }

    private JsonObject pair(Wire.Request request) {
        Wire.Response response = dispatcher.dispatch(request, "s1");
        var pair = new JsonObject();
        pair.add("request", request.toJson());
        pair.add("response", response.toJson());
        return pair;
    }

    private static JsonObject payload(String key, String value) {
        var object = new JsonObject();
        object.addProperty(key, value);
        return object;
    }

    private static JsonObject allPlayers() {
        var object = payload("resource", SHOP);
        object.addProperty("allPlayers", true);
        return object;
    }

    private static String pretty(JsonObject object) {
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        return gson.toJson(object) + "\n";
    }
}
