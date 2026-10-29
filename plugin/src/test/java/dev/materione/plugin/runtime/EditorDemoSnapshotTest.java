package dev.materione.plugin.runtime;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.materione.api.platform.PlatformDescriptor;
import dev.materione.api.scheduling.SchedulingGateway;
import dev.materione.core.diagnostics.DiagnosticsBusService;
import dev.materione.core.diagnostics.QuarantineTracker;
import dev.materione.core.document.AssetStore;
import dev.materione.core.document.AuditLog;
import dev.materione.core.document.DocumentStore;
import dev.materione.core.document.ModelTransactionService;
import dev.materione.core.registry.RegistryService;
import dev.materione.core.settings.SettingsService;
import dev.materione.core.storage.RestartStaging;
import dev.materione.core.storage.StorageService;
import dev.materione.plugin.runtime.api.AdminApiImpl;
import dev.materione.plugin.runtime.api.DeliveryApiImpl;
import dev.materione.plugin.runtime.api.GlyphMetadataComputer;
import dev.materione.plugin.runtime.api.PipelineApiImpl;
import dev.materione.plugin.runtime.mechanics.MechanicsService;
import dev.materione.testing.golden.GoldenFiles;
import dev.materione.web.protocol.Wire;
import dev.materione.web.rpc.RpcDispatcher;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Records the cloud editor's demo snapshot (web.md §6, cloud landing): the
 * real editor op set — the same {@link EditorTransportService#opsDispatcher}
 * a live server serves — over the {@code sample} and {@code world} fixture
 * packs, with the real content types, mechanics and behaviour vocabulary.
 * The browser demo replays this snapshot, so its forms, catalog, textures
 * and models are exactly what a server with these packs shows. A golden
 * file: any change to the ops, the registries or the packs shows up as a
 * diff here (regenerate with {@code -Dmaterione.golden.regen=true}).
 */
class EditorDemoSnapshotTest {

    static final String GOLDEN = "editor-demo-snapshot.json";
    static final List<String> PACKS = List.of("sample", "world");
    static final String PLATFORM_VERSION = "1.21.4";
    static final String SCAFFOLD_ID = "demo_scaffold";

    private static final List<Runnable> CLOSE = new ArrayList<>();
    private static final int[] LIVE = new int[2];

    @TempDir
    Path temp;

    @org.junit.jupiter.api.AfterEach
    void close() {
        CLOSE.forEach(Runnable::run);
        CLOSE.clear();
    }

    @Test
    void demoSnapshotMatchesGolden() throws IOException {
        Path root = temp.resolve("server");
        StorageService.StorageState storage = new StorageService().init(root);
        for (String pack : PACKS) {
            copyTree(GoldenFiles.fixturesRoot().resolve("packs").resolve(pack),
                    storage.contentPacksDir().resolve(pack));
        }

        RpcDispatcher dispatcher = realOps(root, storage);
        // Server-side maps are often Map.of/HashMap: canonical key order, except
        // each document's parsed tree, whose order is the file's (and the form's).
        JsonObject snapshot = sorted(record(dispatcher)).getAsJsonObject();

        String json = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
                .toJson(snapshot) + "\n";
        // Machine paths never leak into the golden (diagnostic locations).
        json = json.replace(jsonPath(storage.contentPacksDir()), "plugins/Materione/content/packs")
                .replace(jsonPath(root), "plugins/Materione");
        Path actual = temp.resolve(GOLDEN);
        Files.writeString(actual, json, StandardCharsets.UTF_8);
        if (GoldenFiles.regenEnabled()) {
            GoldenFiles.writeGolden(actual, GOLDEN);
        }
        GoldenFiles.assertMatchesGolden(actual, GOLDEN);
    }

    /** The plugin's own wiring (MaterionePlugin#wireApi), headless. */
    private static RpcDispatcher realOps(Path root, StorageService.StorageState storage) throws IOException {
        var diagnostics = new DiagnosticsBusService(256);
        var quarantine = new QuarantineTracker();
        var registries = new RegistryService();
        SchedulingGateway scheduling = new DirectScheduling();
        var descriptor = new PlatformDescriptor(PLATFORM_VERSION, false, false, false, Set.of());
        Plugin host = host();

        var pipeline = new PipelineService(null, diagnostics, quarantine, registries, scheduling, descriptor);
        pipeline.registerBuiltins(itemMaterials());
        var mechanics = new MechanicsService(host, diagnostics, scheduling, pipeline.runtime(), registries);
        mechanics.registerNatives(registries);
        var logic = dev.materione.plugin.logic.LogicTestSupport.withBuiltIns(registries);

        var settingsService = new SettingsService();
        var settings = settingsService.load(root.resolve("settings"));
        var delivery = new DeliveryService(null, diagnostics, scheduling);
        delivery.start(settings);
        var restartStaging = RestartStaging.open(storage.storageDir().resolve("staged-restart"));
        var summary = pipeline.reload(storage.contentPacksDir(), storage.generatedDir(), storage.storageDir());
        var pipelineApi = new PipelineApiImpl(null, scheduling, pipeline, quarantine, restartStaging,
                delivery, storage.contentPacksDir(), storage.generatedDir(), storage.storageDir(), null);

        Path packsRoot = storage.contentPacksDir();
        var documents = new DocumentStore(packsRoot, storage.storageDir().resolve("revisions"));
        var audit = new AuditLog(storage.storageDir().resolve("audit.log"));
        var assetStore = new AssetStore(packsRoot, audit);
        var authoring = new dev.materione.core.authoring.AuthoringApiImpl(
                documents, audit, packsRoot, new ModelTransactionService(documents, packsRoot, audit),
                new GlyphMetadataComputer(pipeline), () -> pipeline.runner().lastGraph(),
                (actor, pack, changed) -> { }, assetStore, pipeline::contentTypes,
                // the GUI surface as MaterionePlugin wires it
                base -> new dev.materione.content.gui.GuiAuthoringApiImpl(
                        base,
                        registries.registry(dev.materione.api.registry.RegistryKeys.GUI_COMPONENTS,
                                dev.materione.api.gui.GuiComponent.class),
                        packsRoot,
                        () -> pipeline.allocation().preview(),
                        List.of()));
        var assets = new dev.materione.core.assets.AssetApiImpl(assetStore, packsRoot, (actor, pack, changed) -> { });
        EditorTransportService[] transport = new EditorTransportService[1];
        var admin = new AdminApiImpl(() -> null, registries, diagnostics, quarantine,
                () -> pipeline, () -> restartStaging, () -> delivery, root.resolve("settings"), audit,
                () -> transport[0], settingsService, () -> settings, () -> storage, () -> null);
        var api = new dev.materione.core.MaterioneApiImpl(
                "demo", registries, diagnostics, () -> descriptor, scheduling,
                authoring, assets, pipelineApi, new DeliveryApiImpl(delivery),
                // the runtime surface is wired but unused here: its constructor touches no server
                new dev.materione.plugin.runtime.api.RuntimeApiImpl(null, scheduling, pipeline, () -> null, () -> null),
                admin, mechanics, null, logic);
        transport[0] = new EditorTransportService(
                null, diagnostics, scheduling, pipeline, api, documents, audit, authoring);
        // A fresh boot publishes its compiled pack (MaterionePlugin#loadContent).
        if (summary.packZip() != null) {
            delivery.publish(summary.packZip(), summary.packSha256());
        }
        CLOSE.add(delivery::stop);
        LIVE[0] = summary.itemsLive();
        LIVE[1] = summary.quarantined();
        return transport[0].opsDispatcher(storage);
    }

    /**
     * The material palette ItemMaterialPalette reports on a live server. Its
     * Material#isItem needs the server's registries, so this reads the same
     * set from the API's generated ItemType constants (names only, never
     * initialised).
     */
    static List<String> itemMaterials() {
        return java.util.Arrays.stream(org.bukkit.inventory.ItemType.class.getFields())
                .filter(field -> java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                .filter(field -> org.bukkit.inventory.ItemType.class.isAssignableFrom(field.getType()))
                .map(java.lang.reflect.Field::getName)
                .filter(name -> !name.equals("AIR"))
                .sorted()
                .toList();
    }

    private static JsonObject record(RpcDispatcher ops) {
        JsonObject snapshot = new JsonObject();
        snapshot.addProperty("format", 1);
        snapshot.addProperty("source",
                "Recorded from the real editor ops over fixtures/packs/" + String.join(" + ", PACKS)
                        + " by EditorDemoSnapshotTest; regenerate with -Dmaterione.golden.regen=true.");
        JsonObject session = data(ops, "session.info", new JsonObject());
        // Host facts, not content: no listener, no jar version churn in the golden.
        session.remove("editorPort");
        session.remove("editorRunning");
        session.addProperty("serverVersion", "demo");
        snapshot.add("session", session);
        snapshot.add("describe", data(ops, "authoring.describe", new JsonObject()));
        snapshot.add("types", data(ops, "browse.types", new JsonObject()).get("types"));
        // The delivery backend's port negotiation depends on the recording machine.
        JsonArray diagnostics = new JsonArray();
        for (JsonElement entry : data(ops, "admin.diagnostics.recent", new JsonObject()).getAsJsonArray("diagnostics")) {
            String message = entry.getAsJsonObject().get("message").getAsString();
            if (!message.contains("delivery port") && !message.contains("pack published at")) {
                diagnostics.add(entry);
            }
        }
        snapshot.add("diagnostics", diagnostics);
        snapshot.add("delivery", data(ops, "delivery.status", new JsonObject()));
        JsonObject settings = data(ops, "admin.settings.get", new JsonObject());
        settings.add("parsed", sorted(settings.get("parsed"))); // a hash map server-side, not a file
        snapshot.add("settings", settings);

        JsonArray packs = new JsonArray();
        for (JsonElement name : data(ops, "browse.packs", new JsonObject()).getAsJsonArray("packs")) {
            String pack = name.getAsString();
            JsonObject packJson = new JsonObject();
            packJson.addProperty("name", pack);
            // Directory-walk order differs between filesystems: sort for a portable golden.
            packJson.add("folders", sortedBy(data(ops, "browse.folders", payload("pack", pack)).getAsJsonArray("folders")));
            JsonArray resources = sortedBy(data(ops, "browse.resources", payload("pack", pack)).getAsJsonArray("resources"));
            packJson.add("resources", resources);
            JsonObject summaryRequest = payload("pack", pack);
            summaryRequest.addProperty("limit", 200);
            packJson.add("summaries", sortedBy(data(ops, "authoring.summaries", summaryRequest).getAsJsonArray("resources")));

            Set<String> paths = new LinkedHashSet<>();
            resources.forEach(resource -> paths.add(resource.getAsJsonObject().get("path").getAsString()));
            JsonObject docs = new JsonObject();
            for (String path : paths.stream().sorted().toList()) {
                JsonObject request = payload("pack", pack);
                request.addProperty("path", path);
                docs.add(path, data(ops, "browse.get", request));
            }
            packJson.add("documents", docs);

            JsonArray assets = new JsonArray();
            for (JsonElement asset : data(ops, "assets.list", payload("pack", pack)).getAsJsonArray("assets")) {
                JsonObject entry = asset.getAsJsonObject().deepCopy();
                JsonObject request = payload("pack", pack);
                request.addProperty("path", entry.get("path").getAsString());
                entry.add("data", data(ops, "assets.get", request).get("data"));
                assets.add(entry);
            }
            packJson.add("assets", sortedBy(assets));
            packs.add(packJson);
        }
        snapshot.add("packs", packs);

        // New-resource starters, keyed by type directory, for the placeholder id.
        JsonObject scaffolds = new JsonObject();
        for (String dir : dev.materione.web.rpc.ops.Scaffolds.SCAFFOLD_TYPES) {
            scaffolds.addProperty(dir, dev.materione.web.rpc.ops.Scaffolds.forPath(
                    dir + "/" + SCAFFOLD_ID + ".yml", SCAFFOLD_ID));
        }
        snapshot.addProperty("scaffoldId", SCAFFOLD_ID);
        snapshot.add("scaffolds", scaffolds);
        // What the push.reload after a save reports (the boot reload's counts).
        JsonObject reload = new JsonObject();
        reload.addProperty("itemsLive", LIVE[0]);
        reload.addProperty("quarantined", LIVE[1]);
        snapshot.add("reload", reload);
        return snapshot;
    }

    private static JsonObject data(RpcDispatcher ops, String op, JsonObject payload) {
        Wire.Response response = ops.dispatch(new Wire.Request(op, "demo", payload));
        if (!response.ok()) {
            throw new AssertionError(op + " failed: " + response.error());
        }
        return response.data().getAsJsonObject();
    }

    private static JsonElement sorted(JsonElement element) {
        if (element == null) {
            return null;
        }
        if (element.isJsonArray()) {
            JsonArray out = new JsonArray();
            element.getAsJsonArray().forEach(entry -> out.add(sorted(entry)));
            return out;
        }
        if (!element.isJsonObject()) {
            return element;
        }
        JsonObject in = element.getAsJsonObject();
        JsonObject out = new JsonObject();
        in.keySet().stream().sorted().forEach(key -> out.add(key,
                key.equals("parsed") && in.has("raw") ? in.get(key) : sorted(in.get(key))));
        return out;
    }

    /** Strings by value; objects by path, then id (the DemoServer keeps the same order). */
    private static JsonArray sortedBy(JsonArray array) {
        List<JsonElement> entries = new ArrayList<>();
        array.forEach(entries::add);
        entries.sort(java.util.Comparator.comparing(EditorDemoSnapshotTest::sortKey));
        JsonArray out = new JsonArray();
        entries.forEach(out::add);
        return out;
    }

    private static String sortKey(JsonElement entry) {
        if (!entry.isJsonObject()) {
            return entry.getAsString();
        }
        JsonObject object = entry.getAsJsonObject();
        return (object.has("path") ? object.get("path").getAsString() : "") + " "
                + (object.has("id") ? object.get("id").getAsString() : "");
    }

    private static JsonObject payload(String key, String value) {
        JsonObject payload = new JsonObject();
        payload.addProperty(key, value);
        return payload;
    }

    private static String jsonPath(Path path) {
        // as it appears inside a JSON string literal (Windows separators escaped)
        return path.toAbsolutePath().toString().replace("\\", "\\\\");
    }

    private static void copyTree(Path from, Path to) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(from)) {
            walk.filter(Files::isRegularFile).forEach(files::add);
        }
        for (Path file : files) {
            Path target = to.resolve(from.relativize(file).toString());
            Files.createDirectories(target.getParent());
            Files.copy(file, target);
        }
    }

    /** An enabled host plugin whose server accepts listener registration and ignores it. */
    private static Plugin host() {
        PluginManager manager = proxy(PluginManager.class, (method, args) -> null);
        Server server = proxy(Server.class, (method, args) -> switch (method) {
            case "getPluginManager" -> manager;
            case "isPrimaryThread" -> true;
            default -> null;
        });
        return proxy(Plugin.class, (method, args) -> switch (method) {
            case "getName" -> "Materione";
            case "isEnabled" -> true;
            case "getServer" -> server;
            case "getLogger" -> java.util.logging.Logger.getLogger("materione-demo");
            default -> null;
        });
    }

    @FunctionalInterface
    private interface Answer {
        Object answer(String method, Object[] args);
    }

    private static <T> T proxy(Class<T> type, Answer answer) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (instance, method, args) -> switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(instance);
                    case "equals" -> instance == args[0];
                    case "toString" -> type.getSimpleName() + "Stub";
                    default -> {
                        Object value = answer.answer(method.getName(), args);
                        if (value == null && method.getReturnType() == boolean.class) {
                            yield false;
                        }
                        yield value;
                    }
                }));
    }

    private static final class DirectScheduling implements SchedulingGateway {
        @Override
        public void executeSync(Plugin plugin, Location location, Runnable task) {
            task.run();
        }

        @Override
        public void executeEntity(Plugin plugin, Entity entity, Runnable task) {
            task.run();
        }

        @Override
        public void executeAsync(Plugin plugin, Runnable task) {
            task.run();
        }

        /**
         * World-facing apply (recipes, live stacks) needs a running server;
         * the editor ops read documents and the compiled graph, which are
         * built before apply, so a headless apply that stops early is fine.
         */
        @Override
        public void executeGlobal(Plugin plugin, Runnable task) {
            try {
                task.run();
            } catch (LinkageError headless) {
                // no server registries in a unit test
            }
        }
    }
}
