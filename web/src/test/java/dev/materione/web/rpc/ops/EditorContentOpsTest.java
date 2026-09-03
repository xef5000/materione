package dev.materione.web.rpc.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import dev.materione.core.document.AuditLog;
import dev.materione.core.document.DocumentStore;
import dev.materione.web.ReloadTrigger;
import dev.materione.web.protocol.Wire;
import dev.materione.web.rpc.RpcDispatcher;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The RPC surface against a real store: JSON in, revisions out, conflicts as
 * MTR-0034, syntax rejections as MTR-0007 — and every save queues a targeted
 * reload of exactly the touched file.
 */
class EditorContentOpsTest {

    @TempDir
    Path temp;

    @Test
    void browseEditAndReloadQueueingWorkEndToEnd() throws Exception {
        DocumentStore store = store();
        AuditLog audit = audit();
        CapturingTrigger trigger = new CapturingTrigger();

        RpcDispatcher dispatcher = new RpcDispatcher();
        EditorContentOps.register(dispatcher, store, audit, temp.resolve("packs"), trigger,
                java.util.List::of, java.util.List::of);

        Wire.Response packs = dispatcher.dispatch(request("browse.packs", "1"));
        assertThat(packs.data().getAsJsonObject().get("packs").toString()).isEqualTo("[]");

        assertThat(dispatcher
                .dispatch(request("edit.createPack", "2", object("pack", "demo"))).ok())
                .isTrue();
        // Manifest creation queues no reload at v1; saves do.
        assertThat(trigger.files).isEmpty();

        Wire.Response put = dispatcher.dispatch(request("edit.put", "3",
                object("pack", "demo", "path", "items/wand.yml", "rev", "0",
                        "content", "wand:\n  material: STICK\n")));
        assertThat(put.ok()).isTrue();
        assertThat(payload(put).get("rev").getAsInt()).isEqualTo(1);
        // The save loop: exactly the touched file queued for targeted reload.
        assertThat(trigger.files).containsExactly(
                temp.resolve("packs").resolve("demo").resolve("items").resolve("wand.yml"));

        Wire.Response got = dispatcher.dispatch(request("browse.get", "4",
                object("pack", "demo", "path", "items/wand.yml")));
        assertThat(got.ok()).isTrue();
        assertThat(payload(got).get("raw").getAsString()).contains("STICK");
        assertThat(payload(got).get("rev").getAsInt()).isEqualTo(1);

        var resources = dispatcher.dispatch(request("browse.resources", "5",
                object("pack", "demo")));
        assertThat(resources.ok()).isTrue();
        assertThat(payload(resources).get("resources").toString()).contains("wand");

        // Stale write → visible conflict naming the current revision.
        Wire.Response conflict = dispatcher.dispatch(request("edit.put", "6",
                object("pack", "demo", "path", "items/wand.yml", "rev", "0",
                        "content", "wand:\n  material: BLAZE_ROD\n")));
        assertThat(conflict.ok()).isFalse();
        assertThat(conflict.error().code()).isEqualTo("MTR-0034");

        // Syntax rejection → MTR-0007 before anything touches disk.
        Wire.Response syntax = dispatcher.dispatch(request("edit.put", "7",
                object("pack", "demo", "path", "items/bad.yml", "rev", "0",
                        "content", "a: [broken\n")));
        assertThat(syntax.ok()).isFalse();
        assertThat(syntax.error().code()).isEqualTo("MTR-0007");

        var history = dispatcher.dispatch(request("browse.history", "8",
                object("pack", "demo", "path", "items/wand.yml")));
        assertThat(payload(history).get("revisions").toString()).contains("\"action\":\"write\"");

        String auditText = java.nio.file.Files.readString(temp.resolve("audit.log"));
        assertThat(auditText).contains("edit.put");
        assertThat(auditText).contains("edit.put.conflict");
    }

    @Test
    void authoringCommitWritesOneRevisionAndQueuesOneReload() throws Exception {
        DocumentStore store = store();
        AuditLog audit = audit();
        CapturingTrigger trigger = new CapturingTrigger();
        RpcDispatcher dispatcher = new RpcDispatcher();
        EditorContentOps.register(dispatcher, store, audit, temp.resolve("packs"), trigger,
                java.util.List::of, java.util.List::of);
        dispatcher.dispatch(request("edit.createPack", "1", object("pack", "demo")));
        dispatcher.dispatch(request("edit.create", "2", object("pack", "demo", "path", "items/wand.yml")));
        trigger.files.clear();

        Wire.Response committed = dispatcher.dispatch(request("authoring.commit", "3",
                object("pack", "demo", "path", "items/wand.yml", "rev", "1",
                        "content", "wand:\n  material: BLAZE_ROD\n  display_name: Wand\n")));

        assertThat(committed.ok()).isTrue();
        assertThat(payload(committed).get("rev").getAsInt()).isEqualTo(2);
        assertThat(store.read("demo", "items/wand.yml").raw()).contains("BLAZE_ROD");
        assertThat(trigger.files).containsExactly(temp.resolve("packs/demo/items/wand.yml"));
        assertThat(java.nio.file.Files.readString(temp.resolve("audit.log"))).contains("authoring.commit");
    }

    @Test
    void createResourceCreatesProjectAndNamedItemAsOneOperation() throws Exception {
        DocumentStore store = store();
        CapturingTrigger trigger = new CapturingTrigger();
        RpcDispatcher dispatcher = new RpcDispatcher();
        EditorContentOps.register(dispatcher, store, audit(), temp.resolve("packs"), trigger,
                java.util.List::of, java.util.List::of);

        JsonObject body = object(
                "pack", "demo", "id", "ruby_wand", "name", "Ruby \"Wand\"");
        body.addProperty("createPack", true);
        Wire.Response response =
                dispatcher.dispatch(request("authoring.createResource", "1", body));

        assertThat(response.ok()).isTrue();
        assertThat(payload(response).get("rev").getAsInt()).isEqualTo(1);
        assertThat(store.listPacks()).extracting(DocumentStore.PackSummary::id)
                .containsExactly("demo");
        assertThat(store.read("demo", "items/ruby_wand.yml").raw())
                .contains("display_name: \"Ruby \\\"Wand\\\"\"");
        assertThat(trigger.files).containsExactly(temp.resolve("packs/demo/items/ruby_wand.yml"));
        assertThat(java.nio.file.Files.readString(temp.resolve("audit.log")))
                .contains("authoring.createResource");
    }

    @Test
    void createResourceHonoursTheTypeTabAndFolderTheAuthorIsBrowsing() throws Exception {
        DocumentStore store = store();
        CapturingTrigger trigger = new CapturingTrigger();
        RpcDispatcher dispatcher = new RpcDispatcher();
        EditorContentOps.register(dispatcher, store, audit(), temp.resolve("packs"), trigger,
                java.util.List::of, java.util.List::of);
        dispatcher.dispatch(request("edit.createPack", "1", object("pack", "demo")));

        Wire.Response block = dispatcher.dispatch(request("authoring.createResource", "2",
                object("pack", "demo", "id", "ruby_ore", "name", "Ruby Ore",
                        "type", "blocks", "folder", "ores/deep")));

        assertThat(block.ok()).isTrue();
        assertThat(payload(block).get("path").getAsString()).isEqualTo("blocks/ores/deep/ruby_ore.yml");
        assertThat(store.read("demo", "blocks/ores/deep/ruby_ore.yml").raw())
                .contains("technique:")
                .contains("Ruby Ore");

        // A folder already prefixed with its type is accepted once, not twice.
        Wire.Response prefixed = dispatcher.dispatch(request("authoring.createResource", "3",
                object("pack", "demo", "id", "ruby_pick", "name", "Ruby Pick",
                        "type", "items", "folder", "items/tools")));
        assertThat(payload(prefixed).get("path").getAsString()).isEqualTo("items/tools/ruby_pick.yml");

        // Recipes have no display name; one must not be invented for them.
        Wire.Response recipe = dispatcher.dispatch(request("authoring.createResource", "4",
                object("pack", "demo", "id", "ruby_pick_recipe", "name", "Ruby Pick",
                        "type", "recipes")));
        assertThat(recipe.ok()).isTrue();
        assertThat(payload(recipe).get("raw").getAsString()).doesNotContain("display_name");

        // Unknown types and path traversal are refused before touching disk.
        assertThat(dispatcher.dispatch(request("authoring.createResource", "5",
                object("pack", "demo", "id", "x2", "name", "X", "type", "assets"))).ok()).isFalse();
        assertThat(dispatcher.dispatch(request("authoring.createResource", "6",
                object("pack", "demo", "id", "x2", "name", "X", "type", "items",
                        "folder", "../../etc"))).ok()).isFalse();
    }

    @Test
    void duplicateCopiesBytesUnderANewIdAndKeepsComments() throws Exception {
        DocumentStore store = store();
        RpcDispatcher dispatcher = new RpcDispatcher();
        EditorContentOps.register(dispatcher, store, audit(), temp.resolve("packs"),
                new CapturingTrigger(), java.util.List::of, java.util.List::of);
        dispatcher.dispatch(request("edit.createPack", "1", object("pack", "demo")));
        dispatcher.dispatch(request("edit.put", "2", object(
                "pack", "demo", "path", "items/wand.yml", "rev", "0",
                "content", "# a comment worth keeping\nwand:\n  material: STICK\n")));

        Wire.Response copy = dispatcher.dispatch(request("authoring.duplicateResource", "3",
                object("pack", "demo", "path", "items/wand.yml", "id", "wand_copy")));

        assertThat(copy.ok()).isTrue();
        String raw = store.read("demo", "items/wand_copy.yml").raw();
        assertThat(raw).contains("# a comment worth keeping");
        assertThat(raw).contains("wand_copy:");
        assertThat(raw).doesNotContain("\nwand:");
        // The source is untouched and a clashing id is refused.
        assertThat(store.read("demo", "items/wand.yml").raw()).contains("\nwand:");
        assertThat(dispatcher.dispatch(request("authoring.duplicateResource", "4",
                object("pack", "demo", "path", "items/wand.yml", "id", "wand_copy")))
                .error().code()).isEqualTo("MTR-0008");
    }

    @Test
    void foldersAreListedCreatedMovedAndOnlyDeletedWhenEmpty() throws Exception {
        DocumentStore store = store();
        RpcDispatcher dispatcher = new RpcDispatcher();
        EditorContentOps.register(dispatcher, store, audit(), temp.resolve("packs"),
                new CapturingTrigger(), java.util.List::of, java.util.List::of);
        dispatcher.dispatch(request("edit.createPack", "1", object("pack", "demo")));

        assertThat(dispatcher.dispatch(request("edit.createFolder", "2",
                object("pack", "demo", "path", "items/weapons"))).ok()).isTrue();
        assertThat(payload(dispatcher.dispatch(request("browse.folders", "3",
                object("pack", "demo")))).get("folders").toString())
                .contains("items/weapons");

        // An empty folder is a real place to create in.
        dispatcher.dispatch(request("authoring.createResource", "4",
                object("pack", "demo", "id", "sabre", "name", "Sabre",
                        "type", "items", "folder", "items/weapons")));
        assertThat(store.read("demo", "items/weapons/sabre.yml").revision()).isEqualTo(1);

        // A folder with content is never dropped.
        Wire.Response occupied = dispatcher.dispatch(request("edit.deleteFolder", "5",
                object("pack", "demo", "path", "items/weapons")));
        assertThat(occupied.ok()).isFalse();
        assertThat(occupied.error().code()).isEqualTo("MTR-0008");

        // Renaming carries the documents along, with honest history at the new path.
        assertThat(dispatcher.dispatch(request("edit.moveFolder", "6",
                object("pack", "demo", "from", "items/weapons", "to", "items/blades"))).ok())
                .isTrue();
        assertThat(store.read("demo", "items/blades/sabre.yml").raw()).contains("sabre:");
        assertThat(java.nio.file.Files.exists(temp.resolve("packs/demo/items/weapons"))).isFalse();

        // Type roots are not movable, and folders stay inside their own type.
        assertThat(dispatcher.dispatch(request("edit.moveFolder", "7",
                object("pack", "demo", "from", "items", "to", "blocks"))).ok()).isFalse();
        assertThat(dispatcher.dispatch(request("edit.moveFolder", "8",
                object("pack", "demo", "from", "items/blades", "to", "blocks/blades"))).ok())
                .isFalse();

        // Empty is deletable.
        dispatcher.dispatch(request("edit.delete", "9",
                object("pack", "demo", "path", "items/blades/sabre.yml", "rev", "1")));
        assertThat(dispatcher.dispatch(request("edit.deleteFolder", "10",
                object("pack", "demo", "path", "items/blades"))).ok()).isTrue();
    }

    @Test
    void unknownOpsGetACleanErrorNotADisconnect() {
        RpcDispatcher dispatcher = new RpcDispatcher(); // nothing registered
        Wire.Response response = dispatcher.dispatch(request("browse.nonexistent", "x"));
        assertThat(response.ok()).isFalse();
        assertThat(response.error().code()).isEqualTo("MTR-0033");
    }

    @Test
    void createScaffoldsRefuseDuplicatesAndMoveRejectsGhosts() throws Exception {
        DocumentStore store = store();
        AuditLog audit = audit();
        CapturingTrigger trigger = new CapturingTrigger();
        RpcDispatcher dispatcher = new RpcDispatcher();
        EditorContentOps.register(dispatcher, store, audit, temp.resolve("packs"), trigger,
                java.util.List::of, java.util.List::of);
        dispatcher.dispatch(request("edit.createPack", "1", object("pack", "demo")));

        // Scaffolded create: valid YAML, rev 1, reload queued.
        Wire.Response created = dispatcher.dispatch(request("edit.create", "2",
                object("pack", "demo", "path", "items/gem.yml")));
        assertThat(created.ok()).isTrue();
        assertThat(payload(created).get("raw").getAsString()).contains("material:");
        assertThat(payload(created).get("rev").getAsInt()).isEqualTo(1);
        assertThat(store.read("demo", "items/gem.yml").revision()).isEqualTo(1);

        // Duplicate create refused — never clobber.
        Wire.Response duplicate = dispatcher.dispatch(request("edit.create", "3",
                object("pack", "demo", "path", "items/gem.yml")));
        assertThat(duplicate.ok()).isFalse();
        assertThat(duplicate.error().code()).isEqualTo("MTR-0008");

        // No scaffold for files outside the type dirs.
        Wire.Response wrongDir = dispatcher.dispatch(request("edit.create", "4",
                object("pack", "demo", "path", "pack.yml")));
        assertThat(wrongDir.ok()).isFalse();

        // Move: content at the new path, old path gone, both journals truthful.
        Wire.Response moved = dispatcher.dispatch(request("edit.move", "5",
                object("pack", "demo", "from", "items/gem.yml", "to", "items/relic.yml",
                        "rev", "1")));
        assertThat(moved.ok()).isTrue();
        assertThat(payload(moved).get("rev").getAsInt()).isEqualTo(1);
        assertThat(java.nio.file.Files.isRegularFile(
                temp.resolve("packs/demo/items/relic.yml"))).isTrue();
        assertThat(java.nio.file.Files.exists(
                temp.resolve("packs/demo/items/gem.yml"))).isFalse();

        // A stale write against the OLD path must fail — no ghost resurrection.
        Wire.Response ghost = dispatcher.dispatch(request("edit.put", "6",
                object("pack", "demo", "path", "items/gem.yml", "rev", "0",
                        "content", "gem:\n  material: STICK\n")));
        assertThat(ghost.ok()).isFalse();
        assertThat(ghost.error().code()).isEqualTo("MTR-0034");

        // Moving onto an existing target is refused.
        dispatcher.dispatch(request("edit.create", "7",
                object("pack", "demo", "path", "items/other.yml")));
        Wire.Response clash = dispatcher.dispatch(request("edit.move", "8",
                object("pack", "demo", "from", "items/relic.yml", "to", "items/other.yml",
                        "rev", "1")));
        assertThat(clash.ok()).isFalse();

        String auditText = java.nio.file.Files.readString(temp.resolve("audit.log"));
        assertThat(auditText).contains("edit.create");
        assertThat(auditText).contains("edit.move");
    }

    @Test
    void diagnosticsCarrySourceLocationWhenPresent() {
        RpcDispatcher dispatcher = new RpcDispatcher();
        var diagnostic = new dev.materione.api.diagnostics.Diagnostic(
                dev.materione.core.diagnostics.DiagnosticCodes.SETTINGS_PARSE_FAILED,
                dev.materione.api.diagnostics.Severity.ERROR,
                "broken line", null,
                new dev.materione.api.diagnostics.SourceLocation("items/bad.yml", 4, 7),
                "fix it");
        EditorContentOps.register(dispatcher, store(), audit(), temp.resolve("packs"),
                new CapturingTrigger(), java.util.List::of, () -> List.of(diagnostic));

        Wire.Response response = dispatcher.dispatch(request("admin.diagnostics.recent", "1"));
        assertThat(response.ok()).isTrue();
        JsonObject entry = payload(response).get("diagnostics")
                .getAsJsonArray().get(0).getAsJsonObject();
        assertThat(entry.get("location").getAsString()).isEqualTo("items/bad.yml:4:7");
    }

    // ------------------------------------------------------------------

    private DocumentStore store() {
        return new DocumentStore(temp.resolve("packs"), temp.resolve("revisions"));
    }

    private AuditLog audit() {
        return new AuditLog(temp.resolve("audit.log"));
    }

    private static Wire.Request request(String op, String id) {
        return new Wire.Request(op, id, new JsonObject());
    }

    private static Wire.Request request(String op, String id, JsonObject payload) {
        return new Wire.Request(op, id, payload);
    }

    /** Response payloads are JsonElement on the wire shape; ops always send objects. */
    private static JsonObject payload(Wire.Response response) {
        return response.data().getAsJsonObject();
    }

    /** Alternating key/value pairs; values parse as int when purely numeric. */
    private static JsonObject object(String... pairs) {
        JsonObject object = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) {
            String value = pairs[i + 1];
            if (value.matches("-?\\d+")) {
                object.addProperty(pairs[i], Integer.parseInt(value));
            } else {
                object.addProperty(pairs[i], value);
            }
        }
        return object;
    }

    /** Records every queued reload so assertions can pin exact file sets. */
    private static final class CapturingTrigger implements ReloadTrigger {
        private final Set<Path> files = java.util.concurrent.ConcurrentHashMap.newKeySet();

        @Override
        public CompletableFuture<Outcome> reloadFiles(Set<Path> paths) {
            files.addAll(paths);
            return CompletableFuture.completedFuture(new Outcome(true, false, 0, 0));
        }
    }
}
