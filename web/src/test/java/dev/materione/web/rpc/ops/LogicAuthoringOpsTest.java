package dev.materione.web.rpc.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.materione.api.auth.ActorContext;
import dev.materione.api.authoring.AuthoringApi;
import dev.materione.api.logic.ActionDescription;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.ConditionType;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.Threading;
import dev.materione.api.registry.BehaviorVocabulary;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.behavior.logic.LogicLookup;
import dev.materione.behavior.logic.authoring.LogicAuthoringApiImpl;
import dev.materione.core.authoring.AuthoringApiImpl;
import dev.materione.core.document.AuditLog;
import dev.materione.core.document.DocumentStore;
import dev.materione.core.document.ModelTransactionService;
import dev.materione.testing.golden.GoldenFiles;
import dev.materione.web.protocol.Wire;
import dev.materione.web.rpc.RpcDispatcher;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The logic ops ({@code logic.vocabulary}, {@code logic.get}, {@code logic.edit}, {@code logic.validate},
 * {@code logic.instantiate}) through the real dispatcher over a real item document, and the frames they
 * exchange pinned in {@code fixtures/protocol/editor-logic-v1.json}. The SPA's wrappers must produce the
 * request payloads byte for byte (vitest reads the same file). Regenerate with
 * {@code -Dmaterione.golden.regen=true}.
 */
class LogicAuthoringOpsTest {

    private static final String FILE_NAME = "editor-logic-v1.json";
    private static final String PACK = "demo";
    private static final String PATH = "items/sword.yml";
    private static final String SEED = """
            sword:
              display_name: Sword
              behavior:
                - on: click:right
                  do:
                    - give: one
                    - stop
            """;

    @TempDir
    Path temp;

    @Test
    void theOpsAnswerLikeTheApi() {
        Session session = session();
        Wire.Response get = session.dispatcher.dispatch(get("l-1"));
        assertThat(get.ok()).as(String.valueOf(get.error())).isTrue();
        assertThat(get.data().getAsJsonObject().getAsJsonArray("rules")).hasSize(1);

        Wire.Response edited = session.dispatcher.dispatch(edit("l-2", 1));
        assertThat(edited.ok()).as(String.valueOf(edited.error())).isTrue();
        assertThat(edited.data().getAsJsonObject().get("rev").getAsInt()).isEqualTo(2);

        Wire.Response stale = session.dispatcher.dispatch(edit("l-3", 1));
        assertThat(stale.ok()).isFalse();
        assertThat(stale.error().code()).isEqualTo("MTR-0034");

        Wire.Response refused = session.dispatcher.dispatch(badStep("l-4", 2));
        assertThat(refused.ok()).isFalse();
        assertThat(refused.error().code()).isEqualTo("MTR-0057");

        Wire.Response validated = session.dispatcher.dispatch(validate("l-5"));
        assertThat(validated.ok()).as(String.valueOf(validated.error())).isTrue();
        assertThat(validated.data().getAsJsonObject().getAsJsonArray("diagnostics")).hasSize(1);
    }

    @Test
    void aMalformedRequestIsABadRequestNotAFailure() {
        Session session = session();
        Wire.Response response = session.dispatcher.dispatch(new Wire.Request("logic.edit", "bad-1",
                payload("pack", PACK, "path", PATH, "rev", 1, "edits", "not-a-list")));
        assertThat(response.ok()).isFalse();
        assertThat(response.error().code()).isEqualTo("MTR-0008");
    }

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
                "missing " + target + " — regenerate once with -Dmaterione.golden.regen=true");
        assertThat(pretty(built)).isEqualTo(Files.readString(target, StandardCharsets.UTF_8));
    }

    /** Runs each frame through the real dispatcher in a fixed order, and records the pairs. */
    private JsonObject build() {
        Session session = session();
        var root = new JsonObject();
        var meta = new JsonObject();
        meta.addProperty("file", FILE_NAME);
        meta.addProperty("name", "materione logic op frames");
        meta.addProperty("version", 1);
        root.add("meta", meta);
        root.add("vocabulary", pair(session.dispatcher, vocabulary("l-0")));
        root.add("get", pair(session.dispatcher, get("l-1")));
        root.add("edit", pair(session.dispatcher, edit("l-2", 1)));
        root.add("editConflict", pair(session.dispatcher, edit("l-3", 1)));
        root.add("editRefused", pair(session.dispatcher, badStep("l-4", 2)));
        root.add("validate", pair(session.dispatcher, validate("l-5")));
        root.add("instantiate", pair(session.dispatcher, instantiate("l-6")));
        return root;
    }

    private static JsonObject pair(RpcDispatcher dispatcher, Wire.Request request) {
        Wire.Response response = dispatcher.dispatch(request);
        var pair = new JsonObject();
        pair.add("request", request.toJson());
        pair.add("response", response.toJson());
        return pair;
    }

    private static Wire.Request vocabulary(String id) {
        return new Wire.Request("logic.vocabulary", id, new JsonObject());
    }

    private static Wire.Request get(String id) {
        return new Wire.Request("logic.get", id, payload("pack", PACK, "path", PATH));
    }

    /** Sets the rule's node position and inserts a step at the front of its {@code do:} list. */
    private static Wire.Request edit(String id, int rev) {
        var position = new JsonObject();
        position.addProperty("op", "setNodePosition");
        position.addProperty("path", "behavior.0");
        position.addProperty("x", 30);
        position.addProperty("y", 40);
        var step = new JsonObject();
        step.addProperty("op", "insertStep");
        step.addProperty("path", "behavior.0.do.0");
        var give = new JsonObject();
        give.addProperty("give", "front");
        step.add("step", give);
        var edits = new JsonArray();
        edits.add(position);
        edits.add(step);
        var body = payload("pack", PACK, "path", PATH);
        body.addProperty("rev", rev);
        body.add("edits", edits);
        return new Wire.Request("logic.edit", id, body);
    }

    /** A step that names no kind the registries know: refused, and nothing is written. */
    private static Wire.Request badStep(String id, int rev) {
        var step = new JsonObject();
        step.addProperty("op", "insertStep");
        step.addProperty("path", "behavior.0.do.0");
        var unknown = new JsonObject();
        unknown.addProperty("nosuch", 1);
        step.add("step", unknown);
        var edits = new JsonArray();
        edits.add(step);
        var body = payload("pack", PACK, "path", PATH);
        body.addProperty("rev", rev);
        body.add("edits", edits);
        return new Wire.Request("logic.edit", id, body);
    }

    /** A draft that is not saved: one rule with a known and an unknown step. */
    private static Wire.Request validate(String id) {
        var known = new JsonObject();
        known.addProperty("give", "ok");
        var unknown = new JsonObject();
        unknown.addProperty("nosuch", 1);
        var steps = new JsonArray();
        steps.add(known);
        steps.add(unknown);
        var rule = new JsonObject();
        rule.addProperty("on", "click");
        rule.add("do", steps);
        var behavior = new JsonArray();
        behavior.add(rule);
        var body = payload("pack", PACK, "resource", "draft");
        body.add("behavior", behavior);
        body.add("flows", new JsonObject());
        return new Wire.Request("logic.validate", id, body);
    }

    private static Wire.Request instantiate(String id) {
        var body = payload("recipe", "pay_to_receive");
        body.add("params", new JsonObject());
        return new Wire.Request("logic.instantiate", id, body);
    }

    private static JsonObject payload(Object... keyValues) {
        var object = new JsonObject();
        for (int i = 0; i < keyValues.length; i += 2) {
            String key = (String) keyValues[i];
            Object value = keyValues[i + 1];
            if (value instanceof Integer number) {
                object.addProperty(key, number);
            } else {
                object.addProperty(key, (String) value);
            }
        }
        return object;
    }

    private record Session(RpcDispatcher dispatcher) {}

    private Session session() {
        Path packsRoot = temp.resolve("packs");
        DocumentStore store = new DocumentStore(packsRoot, temp.resolve("revisions"));
        AuditLog audit = new AuditLog(temp.resolve("audit.log"));
        var authoring = new AuthoringApiImpl(store, audit, packsRoot,
                new ModelTransactionService(store, packsRoot, audit), null, () -> null, (a, p, c) -> { },
                null, List::of, null, base -> new LogicAuthoringApiImpl(base, lookup()));
        var actor = ActorContext.console();
        authoring.createPack(actor, PACK);
        authoring.createDocument(actor, new AuthoringApi.CreateDocumentRequest(PACK, PATH, "sword", "Sword", SEED));
        var dispatcher = new RpcDispatcher();
        LogicAuthoringOps.register(dispatcher, authoring.logic(),
                attribution -> new ActorContext(attribution, ActorContext.Kind.EDITOR_SESSION,
                        Optional.empty(), node -> true),
                BehaviorVocabulary::empty);
        return new Session(dispatcher);
    }

    /** The lookup the ops resolve steps against: one action (give, message) and the click trigger. */
    private static LogicLookup lookup() {
        return new LogicLookup() {
            @Override
            public Optional<ActionType<?>> action(String authoredId) {
                return "give".equals(authoredId) || "message".equals(authoredId)
                        ? Optional.<ActionType<?>>of(new Give()) : Optional.empty();
            }

            @Override
            public Optional<ConditionType<?>> condition(String authoredId) {
                return Optional.empty();
            }

            @Override
            public boolean triggerKnown(String authoredId) {
                return authoredId.equals("click");
            }
        };
    }

    /** A plain action that takes one value; the ops only need it to resolve and parse. */
    private static final class Give implements ActionType<String> {
        @Override
        public String id() {
            return "give";
        }

        @Override
        public ActionDescription describe() {
            return new ActionDescription(new SelfDescription("give", "Gives a value",
                    List.of(ParameterSchema.of("value", ParameterType.STRING, false)), "1.0.0"),
                    LogicCategory.DATA, "value");
        }

        @Override
        public Threading threading() {
            return Threading.ANY;
        }

        @Override
        public String parse(ParamReader params) {
            return params.raw("value").map(String::valueOf).orElse("");
        }

        @Override
        public ActionOutcome execute(String params, LogicContext context) {
            return ActionOutcome.OK;
        }
    }

    private static String pretty(JsonObject object) {
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        return gson.toJson(object) + "\n";
    }
}
