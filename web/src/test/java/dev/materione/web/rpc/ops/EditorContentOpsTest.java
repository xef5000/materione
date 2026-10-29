package dev.materione.web.rpc.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import dev.materione.api.NamespacedId;
import dev.materione.api.auth.ActorContext;
import dev.materione.api.authoring.AuthoringApi;
import dev.materione.api.content.Allocations;
import dev.materione.api.content.ContentType;
import dev.materione.api.content.PackEmitter;
import dev.materione.api.registry.BehaviorVocabulary;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.core.authoring.ReloadHook;
import dev.materione.core.document.AuditLog;
import dev.materione.core.document.DocumentStore;
import dev.materione.web.protocol.Wire;
import dev.materione.web.rpc.RpcDispatcher;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        EditorContentOps.register(dispatcher, api(store, audit, trigger),
                java.util.List::of, java.util.List::of, EditorContentOpsTest::actor);

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
    void summariesAreOneBoundedReadOnlyRpcPage() throws Exception {
        Path pack = temp.resolve("packs/demo");
        java.nio.file.Files.createDirectories(pack.resolve("items"));
        java.nio.file.Files.writeString(pack.resolve("pack.yml"), "id: demo\n");
        java.nio.file.Files.writeString(pack.resolve("items/catalog.yml"), """
                one:
                  material: PAPER
                two:
                  material: STICK
                """);
        CapturingTrigger trigger = new CapturingTrigger();
        RpcDispatcher dispatcher = new RpcDispatcher();
        EditorContentOps.register(dispatcher, api(store(), audit(), trigger),
                java.util.List::of, java.util.List::of, EditorContentOpsTest::actor);

        Wire.Response first = dispatcher.dispatch(request("authoring.summaries", "1",
                object("pack", "demo", "type", "items", "limit", "1")));
        assertThat(first.ok()).isTrue();
        JsonObject firstRow = payload(first).getAsJsonArray("resources").get(0).getAsJsonObject();
        assertThat(firstRow.get("id").getAsString()).isEqualTo("one");
        assertThat(firstRow.get("name").getAsString()).isEqualTo("One");
        assertThat(firstRow.get("health").getAsString()).isEqualTo("unknown");
        assertThat(payload(first).get("total").getAsInt()).isEqualTo(2);
        assertThat(payload(first).get("nextOffset").getAsInt()).isEqualTo(1);

        Wire.Response second = dispatcher.dispatch(request("authoring.summaries", "2",
                object("pack", "demo", "type", "items", "offset", "1", "limit", "1")));
        assertThat(payload(second).getAsJsonArray("resources").get(0)
                .getAsJsonObject().get("id").getAsString()).isEqualTo("two");

        Wire.Response invalid = dispatcher.dispatch(request("authoring.summaries", "3",
                object("pack", "demo", "limit", "201")));
        assertThat(invalid.ok()).isFalse();
        assertThat(invalid.error().code()).isEqualTo("MTR-0008");
        assertThat(temp.resolve("revisions")).doesNotExist();
        assertThat(temp.resolve("audit.log")).doesNotExist();
        assertThat(trigger.files).isEmpty();
    }

    @Test
    void authoringCommitWritesOneRevisionAndQueuesOneReload() throws Exception {
        DocumentStore store = store();
        AuditLog audit = audit();
        CapturingTrigger trigger = new CapturingTrigger();
        RpcDispatcher dispatcher = new RpcDispatcher();
        EditorContentOps.register(dispatcher, api(store, audit, trigger),
                java.util.List::of, java.util.List::of, EditorContentOpsTest::actor);
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
        EditorContentOps.register(dispatcher, api(store, audit(), trigger),
                java.util.List::of, java.util.List::of, EditorContentOpsTest::actor);

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
        EditorContentOps.register(dispatcher, api(store, audit(), trigger),
                java.util.List::of, java.util.List::of, EditorContentOpsTest::actor);
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
        EditorContentOps.register(dispatcher, api(store, audit(), new CapturingTrigger()), java.util.List::of, java.util.List::of,
                EditorContentOpsTest::actor);
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
    void templatesAreACreationChoiceDetachableAndSavableOverTheWire() throws Exception {
        DocumentStore store = store();
        CapturingTrigger trigger = new CapturingTrigger();
        RpcDispatcher dispatcher = new RpcDispatcher();
        EditorContentOps.register(dispatcher, api(store, audit(), trigger),
                java.util.List::of, java.util.List::of, EditorContentOpsTest::actor);
        dispatcher.dispatch(request("edit.createPack", "1", object("pack", "demo")));
        dispatcher.dispatch(request("edit.put", "2", object(
                "pack", "demo", "path", "templates/base_item.yml", "rev", "0",
                "content", "base_item:\n  material: PAPER\n  lore:\n    - Forged\n")));

        // create from the template: the file is the link plus the name
        Wire.Response created = dispatcher.dispatch(request("authoring.createResource", "3",
                object("pack", "demo", "id", "ruby_dagger", "name", "Ruby Dagger",
                        "type", "items", "template", "base_item")));
        assertThat(created.ok()).isTrue();
        assertThat(store.read("demo", "items/ruby_dagger.yml").raw())
                .contains("template: base_item").contains("display_name: Ruby Dagger")
                .doesNotContain("material");
        assertThat(dispatcher.dispatch(request("authoring.createResource", "4",
                object("pack", "demo", "id", "ghost", "name", "Ghost", "template", "nope"))).ok())
                .isFalse();

        // the catalog lists the template and who uses it
        JsonObject listed = payload(dispatcher.dispatch(request("authoring.templates", "5")));
        JsonObject row = listed.getAsJsonArray("templates").get(0).getAsJsonObject();
        assertThat(row.get("id").getAsString()).isEqualTo("base_item");
        assertThat(row.getAsJsonObject("definition").get("material").getAsString()).isEqualTo("PAPER");
        assertThat(row.getAsJsonArray("usedBy")).hasSize(1);

        // save a template from the dagger's own fields, then detach it
        dispatcher.dispatch(request("authoring.setFields", "6", editsOf("demo", "items/ruby_dagger.yml", 1,
                "[{\"field\":[\"ruby_dagger\",\"power\"],\"value\":\"7\"}]")));
        Wire.Response saved = dispatcher.dispatch(request("authoring.saveAsTemplate", "7",
                saveBody("demo", "items/ruby_dagger.yml", "ruby_dagger", "dagger_base", "power")));
        assertThat(saved.ok()).isTrue();
        assertThat(payload(saved).get("path").getAsString()).isEqualTo("templates/dagger_base.yml");

        Wire.Response detached = dispatcher.dispatch(request("authoring.detachTemplate", "8",
                object("pack", "demo", "path", "items/ruby_dagger.yml", "resource", "ruby_dagger",
                        "rev", "2")));
        assertThat(detached.ok()).isTrue();
        assertThat(store.read("demo", "items/ruby_dagger.yml").raw())
                .doesNotContain("template:").contains("material: PAPER").contains("- Forged")
                .contains("display_name: Ruby Dagger");
        assertThat(java.nio.file.Files.readString(temp.resolve("audit.log")))
                .contains("authoring.saveAsTemplate").contains("authoring.detachTemplate");
        // a second detach has nothing to detach
        assertThat(dispatcher.dispatch(request("authoring.detachTemplate", "9",
                object("pack", "demo", "path", "items/ruby_dagger.yml", "rev", "3"))).ok()).isFalse();
    }

    private static JsonObject editsOf(String pack, String path, int rev, String editsJson) {
        JsonObject body = object("pack", pack, "path", path, "rev", String.valueOf(rev));
        body.add("edits", com.google.gson.JsonParser.parseString(editsJson));
        return body;
    }

    private static JsonObject saveBody(
            String pack, String path, String resource, String id, String... fields) {
        JsonObject body = object("pack", pack, "path", path, "resource", resource, "id", id);
        var array = new com.google.gson.JsonArray();
        for (String field : fields) {
            array.add(field);
        }
        body.add("fields", array);
        return body;
    }

    @Test
    void foldersAreListedCreatedMovedAndOnlyDeletedWhenEmpty() throws Exception {
        DocumentStore store = store();
        RpcDispatcher dispatcher = new RpcDispatcher();
        EditorContentOps.register(dispatcher, api(store, audit(), new CapturingTrigger()), java.util.List::of, java.util.List::of,
                EditorContentOpsTest::actor);
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
        EditorContentOps.register(dispatcher, api(store, audit, trigger),
                java.util.List::of, java.util.List::of, EditorContentOpsTest::actor);
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
                dev.materione.api.diagnostics.DiagnosticCodes.SETTINGS_PARSE_FAILED,
                dev.materione.api.diagnostics.Severity.ERROR,
                "broken line", null,
                new dev.materione.api.diagnostics.SourceLocation("items/bad.yml", 4, 7),
                "fix it");
        EditorContentOps.register(dispatcher, api(store(), audit(), new CapturingTrigger()), java.util.List::of,
                () -> List.of(diagnostic), EditorContentOpsTest::actor);

        Wire.Response response = dispatcher.dispatch(request("admin.diagnostics.recent", "1"));
        assertThat(response.ok()).isTrue();
        JsonObject entry = payload(response).get("diagnostics")
                .getAsJsonArray().get(0).getAsJsonObject();
        assertThat(entry.get("location").getAsString()).isEqualTo("items/bad.yml:4:7");
    }

    @Test
    void browseTypesSerialisesNestedParameterSchemas() {
        var hitboxes = new ParameterSchema("hitboxes", ParameterType.LIST, false, null, null, null, List.of(
                ParameterSchema.of("type", ParameterType.STRING, true),
                ParameterSchema.of("width", ParameterType.INTEGER, false)));
        RpcDispatcher dispatcher = new RpcDispatcher();
        EditorContentOps.register(dispatcher, api(store(), audit(), new CapturingTrigger()),
                () -> List.of(typeWith(hitboxes)), List::of, EditorContentOpsTest::actor);

        Wire.Response response = dispatcher.dispatch(request("browse.types", "1"));

        assertThat(response.ok()).isTrue();
        JsonObject parameter = payload(response).getAsJsonArray("types").get(0).getAsJsonObject()
                .getAsJsonArray("parameters").get(0).getAsJsonObject();
        assertThat(parameter.get("id").getAsString()).isEqualTo("hitboxes");
        var nested = parameter.getAsJsonArray("nested");
        assertThat(nested).hasSize(2);
        assertThat(nested.get(0).getAsJsonObject().get("id").getAsString()).isEqualTo("type");
        assertThat(nested.get(0).getAsJsonObject().get("type").getAsString()).isEqualTo("string");
        assertThat(nested.get(1).getAsJsonObject().get("id").getAsString()).isEqualTo("width");
        assertThat(nested.get(1).getAsJsonObject().get("type").getAsString()).isEqualTo("integer");
    }

    @Test
    void authoringDescribeExposesTypesAndTheBehaviorVocabularyFromTheirRegistries() {
        var vocabulary = new BehaviorVocabulary(
                List.of(new BehaviorVocabulary.Event("click", "Click", "A player clicks.",
                        List.of("right", "left"), List.of("item"))),
                List.of(
                        new BehaviorVocabulary.Action("message", new SelfDescription("Message",
                                "Sends a chat line.", List.of(), "0.1.0", "When the rule fires."),
                                "text", false),
                        new BehaviorVocabulary.Action("delay", SelfDescription.of("Delay", "Waits."), null, true),
                        new BehaviorVocabulary.Action("raw", null, null, false)),
                List.of(ParameterSchema.of("if", ParameterType.RAW, false)),
                List.of(ParameterSchema.of("delay_ticks", ParameterType.INTEGER, false)),
                List.of(new BehaviorVocabulary.Mechanic("materione:food", new SelfDescription("Food",
                        "Edible.", List.of(), "0.1.0", "When eaten."), true, "Materione")));
        RpcDispatcher dispatcher = new RpcDispatcher();
        EditorContentOps.register(dispatcher, api(store(), audit(), new CapturingTrigger()),
                () -> List.of(typeWith()), List::of, EditorContentOpsTest::actor, () -> vocabulary);

        Wire.Response response = dispatcher.dispatch(request("authoring.describe", "1"));

        assertThat(response.ok()).isTrue();
        JsonObject data = payload(response);
        assertThat(data.getAsJsonArray("types").get(0).getAsJsonObject().get("name").getAsString())
                .isEqualTo("thing");
        JsonObject behavior = data.getAsJsonObject("behavior");

        JsonObject click = behavior.getAsJsonArray("events").get(0).getAsJsonObject();
        assertThat(click.get("displayName").getAsString()).isEqualTo("Click");
        assertThat(values(click.getAsJsonArray("details"))).containsExactly("right", "left");
        assertThat(values(click.getAsJsonArray("firedBy"))).containsExactly("item");

        JsonObject message = behavior.getAsJsonArray("actions").get(0).getAsJsonObject();
        assertThat(message.get("displayName").getAsString()).isEqualTo("Message");
        assertThat(message.get("activation").getAsString()).isEqualTo("When the rule fires.");
        assertThat(message.get("shorthand").getAsString()).isEqualTo("text");
        assertThat(message.get("deferred").getAsBoolean()).isFalse();

        JsonObject delay = behavior.getAsJsonArray("actions").get(1).getAsJsonObject();
        assertThat(delay.get("deferred").getAsBoolean()).isTrue();
        assertThat(delay.has("activation")).isFalse();
        assertThat(delay.has("shorthand")).isFalse();

        // an undescribed executor still appears, identified only by its key
        JsonObject raw = behavior.getAsJsonArray("actions").get(2).getAsJsonObject();
        assertThat(raw.get("id").getAsString()).isEqualTo("raw");
        assertThat(raw.has("displayName")).isFalse();

        JsonObject food = behavior.getAsJsonArray("mechanics").get(0).getAsJsonObject();
        assertThat(food.get("id").getAsString()).isEqualTo("materione:food");
        assertThat(food.get("displayName").getAsString()).isEqualTo("Food");
        assertThat(food.get("activation").getAsString()).isEqualTo("When eaten.");
        assertThat(food.get("repeatable").getAsBoolean()).isTrue();
        assertThat(food.get("registeredBy").getAsString()).isEqualTo("Materione");

        assertThat(behavior.getAsJsonArray("ruleOptions").get(0).getAsJsonObject().get("id").getAsString())
                .isEqualTo("if");
        assertThat(behavior.getAsJsonArray("actionOptions").get(0).getAsJsonObject().get("id").getAsString())
                .isEqualTo("delay_ticks");
    }

    @Test
    void authoringDescribeCarriesTheVersionTwoVocabularyAdditivelyAfterTheVersionOneKeys() {
        var self = new SelfDescription("Money", "Has money.",
                List.of(ParameterSchema.of("amount", ParameterType.DECIMAL, true)), "1.0.0", "When tested.");
        var vocabulary = new BehaviorVocabulary(
                List.of(new BehaviorVocabulary.Event("click", "Click", "A player clicks.",
                        List.of("right"), List.of("item"))),
                List.of(new BehaviorVocabulary.Action("take_money", self, "amount", true,
                        dev.materione.api.logic.LogicCategory.ECONOMY, dev.materione.api.logic.Threading.GLOBAL,
                        dev.materione.api.logic.Availability.unavailable("No economy provider is installed"),
                        "Materione")),
                List.of(), List.of(), List.of(),
                List.of(new BehaviorVocabulary.Trigger("click", new dev.materione.api.logic.TriggerDescription(
                        SelfDescription.of("Click", "A player clicks."), List.of("right"), List.of("item"),
                        List.of(ParameterSchema.of("layer", ParameterType.STRING, false)),
                        List.of(dev.materione.api.logic.ContextVariable
                                .of("item.amount", ParameterType.INTEGER, "Stack size").providedBy("item")),
                        true), "Materione")),
                List.of(new BehaviorVocabulary.Condition("has_money",
                        new dev.materione.api.logic.ConditionDescription(self,
                                dev.materione.api.logic.LogicCategory.ECONOMY, "amount"),
                        dev.materione.api.logic.Availability.AVAILABLE, "Materione")),
                List.of(new BehaviorVocabulary.StepKind("if", "If", "Branches.", List.of(), false)),
                List.of(dev.materione.api.logic.LogicCategory.ECONOMY, dev.materione.api.logic.LogicCategory.of("weather")),
                List.of(new BehaviorVocabulary.Function("lower", new dev.materione.api.logic.FunctionDescription(
                        new SelfDescription("Lower", "Lower-cases.",
                                List.of(ParameterSchema.of("text", ParameterType.STRING, true)), "1.0.0"),
                        ParameterType.STRING, false), "Materione")));
        RpcDispatcher dispatcher = new RpcDispatcher();
        EditorContentOps.register(dispatcher, api(store(), audit(), new CapturingTrigger()),
                () -> List.of(typeWith()), List::of, EditorContentOpsTest::actor, () -> vocabulary);

        JsonObject behavior = payload(dispatcher.dispatch(request("authoring.describe", "1")))
                .getAsJsonObject("behavior");

        assertThat(behavior.get("version").getAsInt()).isEqualTo(2);
        // version-1 keys keep their shape
        assertThat(behavior.getAsJsonArray("events").get(0).getAsJsonObject().get("id").getAsString())
                .isEqualTo("click");
        JsonObject action = behavior.getAsJsonArray("actions").get(0).getAsJsonObject();
        assertThat(action.get("deferred").getAsBoolean()).isTrue();
        assertThat(action.get("shorthand").getAsString()).isEqualTo("amount");
        // version-2 additions on the action
        assertThat(action.get("category").getAsString()).isEqualTo("economy");
        assertThat(action.get("threading").getAsString()).isEqualTo("global");
        assertThat(action.get("available").getAsBoolean()).isFalse();
        assertThat(action.get("unavailableReason").getAsString()).isEqualTo("No economy provider is installed");
        assertThat(action.get("registeredBy").getAsString()).isEqualTo("Materione");

        JsonObject trigger = behavior.getAsJsonArray("triggers").get(0).getAsJsonObject();
        assertThat(trigger.get("id").getAsString()).isEqualTo("click");
        assertThat(trigger.get("cancellable").getAsBoolean()).isTrue();
        assertThat(values(trigger.getAsJsonArray("firedBy"))).containsExactly("item");
        assertThat(trigger.getAsJsonArray("filters").get(0).getAsJsonObject().get("id").getAsString())
                .isEqualTo("layer");
        JsonObject variable = trigger.getAsJsonArray("context").get(0).getAsJsonObject();
        assertThat(variable.get("path").getAsString()).isEqualTo("item.amount");
        assertThat(variable.get("type").getAsString()).isEqualTo("integer");
        assertThat(values(variable.getAsJsonArray("hosts"))).containsExactly("item");

        JsonObject condition = behavior.getAsJsonArray("conditions").get(0).getAsJsonObject();
        assertThat(condition.get("id").getAsString()).isEqualTo("has_money");
        assertThat(condition.get("category").getAsString()).isEqualTo("economy");
        assertThat(condition.get("shorthand").getAsString()).isEqualTo("amount");
        assertThat(condition.get("available").getAsBoolean()).isTrue();
        assertThat(condition.has("unavailableReason")).isFalse();
        assertThat(condition.getAsJsonArray("parameters").get(0).getAsJsonObject().get("id").getAsString())
                .isEqualTo("amount");

        JsonObject step = behavior.getAsJsonArray("steps").get(0).getAsJsonObject();
        assertThat(step.get("id").getAsString()).isEqualTo("if");
        assertThat(step.get("supported").getAsBoolean()).isFalse();
        assertThat(behavior.getAsJsonArray("categories").get(1).getAsJsonObject().get("builtIn").getAsBoolean())
                .isFalse();
        JsonObject function = behavior.getAsJsonArray("functions").get(0).getAsJsonObject();
        assertThat(function.get("returns").getAsString()).isEqualTo("string");
        assertThat(function.get("minArguments").getAsInt()).isEqualTo(1);
        assertThat(function.get("maxArguments").getAsInt()).isEqualTo(1);
    }

    @Test
    void authoringDescribeWithoutAVocabularySuppliesEmptyBehaviorArrays() {
        RpcDispatcher dispatcher = new RpcDispatcher();
        EditorContentOps.register(dispatcher, api(store(), audit(), new CapturingTrigger()),
                java.util.List::of, java.util.List::of, EditorContentOpsTest::actor);

        Wire.Response response = dispatcher.dispatch(request("authoring.describe", "1"));

        assertThat(response.ok()).isTrue();
        assertThat(payload(response).getAsJsonArray("types")).isEmpty();
        JsonObject behavior = payload(response).getAsJsonObject("behavior");
        for (String key : List.of("events", "actions", "ruleOptions", "actionOptions", "mechanics",
                "triggers", "conditions", "steps", "categories", "functions")) {
            assertThat(behavior.getAsJsonArray(key)).as(key).isEmpty();
        }
    }

    @Test
    void bulkAndReferencesOpsRoundTripOverTheWire() throws Exception {
        RpcDispatcher dispatcher = new RpcDispatcher();
        EditorContentOps.register(dispatcher, api(store(), audit(), new CapturingTrigger()),
                java.util.List::of, java.util.List::of, EditorContentOpsTest::actor);
        dispatcher.dispatch(request("edit.createPack", "1", object("pack", "demo")));
        dispatcher.dispatch(request("edit.put", "2", object("pack", "demo",
                "path", "items/a.yml", "rev", "0", "content", "a:\n  texture: gem\n")));
        dispatcher.dispatch(request("edit.put", "3", object("pack", "demo",
                "path", "items/b.yml", "rev", "0", "content", "b:\n  texture: gem\n")));

        JsonObject refs = object("pack", "demo", "kind", "asset", "target", "assets/textures/gem.png");
        Wire.Response found = dispatcher.dispatch(request("authoring.references", "4", refs));
        assertThat(found.ok()).isTrue();
        assertThat(payload(found).getAsJsonArray("hits")).hasSize(2);
        assertThat(payload(found).getAsJsonObject("coverage").get("complete").getAsBoolean()).isTrue();

        JsonObject body = com.google.gson.JsonParser.parseString(
                "{\"pack\":\"demo\",\"dryRun\":true,\"operations\":["
                        + "{\"kind\":\"set\",\"path\":\"items/a.yml\",\"rev\":1,\"edits\":[{\"field\":[\"a\",\"texture\"],\"value\":\"ruby\"}]},"
                        + "{\"kind\":\"set\",\"path\":\"items/b.yml\",\"rev\":1,\"edits\":[{\"field\":[\"b\",\"texture\"],\"value\":\"ruby\"}]}]}")
                .getAsJsonObject();
        Wire.Response dry = dispatcher.dispatch(request("authoring.bulk", "5", body));
        assertThat(dry.ok()).isTrue();
        assertThat(payload(dry).get("applied").getAsBoolean()).isFalse();
        assertThat(payload(dry).getAsJsonArray("documents").get(0).getAsJsonObject()
                .getAsJsonArray("changes")).hasSize(1);

        body.addProperty("dryRun", false);
        Wire.Response applied = dispatcher.dispatch(request("authoring.bulk", "6", body));
        assertThat(applied.ok()).isTrue();
        assertThat(payload(applied).get("applied").getAsBoolean()).isTrue();

        Wire.Response stale = dispatcher.dispatch(request("authoring.bulk", "7", body));
        assertThat(stale.ok()).isFalse();
    }

    @Test
    void setFieldsRemovalEditReachesTheApiAsARemovalAndDropsTheKey() throws Exception {
        DocumentStore store = store();
        List<AuthoringApi.SetFieldsRequest> seen = new java.util.ArrayList<>();
        RpcDispatcher dispatcher = new RpcDispatcher();
        EditorContentOps.register(dispatcher,
                recording(api(store, audit(), new CapturingTrigger()), seen),
                java.util.List::of, java.util.List::of, EditorContentOpsTest::actor);
        dispatcher.dispatch(request("edit.createPack", "1", object("pack", "demo")));
        dispatcher.dispatch(request("edit.put", "2", object("pack", "demo",
                "path", "items/wand.yml", "rev", "0",
                "content", "wand:\n  material: STICK\n  display_name: Old Name\n")));

        Wire.Response removed = dispatcher.dispatch(request("authoring.setFields", "3",
                setFieldsBody(1, removal("wand", "display_name"))));

        assertThat(removed.ok()).isTrue();
        assertThat(payload(removed).get("rev").getAsInt()).isEqualTo(2);
        assertThat(payload(removed).get("raw").getAsString()).isEqualTo("wand:\n  material: STICK\n");
        assertThat(seen).hasSize(1);
        AuthoringApi.FieldEdit sent = seen.get(0).edits().get(0);
        assertThat(sent.remove()).isTrue();
        assertThat(sent.value()).isNull();
        assertThat(sent.field()).containsExactly("wand", "display_name");
    }

    @Test
    void setFieldsRemovalRefusesACarriedValueBeforeReachingTheApi() {
        List<AuthoringApi.SetFieldsRequest> seen = new java.util.ArrayList<>();
        RpcDispatcher dispatcher = new RpcDispatcher();
        EditorContentOps.register(dispatcher,
                recording(api(store(), audit(), new CapturingTrigger()), seen),
                java.util.List::of, java.util.List::of, EditorContentOpsTest::actor);
        JsonObject withValue = removal("wand", "display_name");
        withValue.addProperty("value", "x");

        Wire.Response rejected = dispatcher.dispatch(request("authoring.setFields", "1",
                setFieldsBody(0, withValue)));

        assertThat(rejected.ok()).isFalse();
        assertThat(rejected.error().code()).isEqualTo("MTR-0008");
        assertThat(rejected.error().message()).contains("no 'value'");
        assertThat(seen).isEmpty();
    }

    /** A content type whose only interesting part is the parameters it declares. */
    private static ContentType typeWith(ParameterSchema... parameters) {
        return new ContentType() {
            @Override
            public String name() {
                return "thing";
            }

            @Override
            public String directory() {
                return "things";
            }

            @Override
            public SelfDescription describe() {
                return new SelfDescription("Thing", "A test type.", List.of(parameters), "test");
            }

            @Override
            public Optional<Object> parse(
                    NamespacedId id, Map<String, Object> definition,
                    Map<String, Map<String, Object>> templates) {
                return Optional.empty();
            }

            @Override
            public void compile(
                    Object definition, NamespacedId allocatedModelId,
                    PackEmitter emitter, Allocations allocations) {}
        };
    }

    /** Forwards every call to the real api and records the setFields requests it receives. */
    private static AuthoringApi recording(AuthoringApi real, List<AuthoringApi.SetFieldsRequest> seen) {
        return (AuthoringApi) java.lang.reflect.Proxy.newProxyInstance(
                AuthoringApi.class.getClassLoader(), new Class<?>[] {AuthoringApi.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("setFields")) {
                        seen.add((AuthoringApi.SetFieldsRequest) args[1]);
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    /** A {@code setFields} edit that removes the field at {@code path}. */
    private static JsonObject removal(String... path) {
        JsonObject edit = new JsonObject();
        var field = new com.google.gson.JsonArray();
        for (String segment : path) {
            field.add(segment);
        }
        edit.add("field", field);
        edit.addProperty("remove", true);
        return edit;
    }

    /** A {@code setFields} body for the demo document at the given revision. */
    private static JsonObject setFieldsBody(int revision, JsonObject... edits) {
        JsonObject body = object("pack", "demo", "path", "items/wand.yml", "rev", String.valueOf(revision));
        var array = new com.google.gson.JsonArray();
        for (JsonObject edit : edits) {
            array.add(edit);
        }
        body.add("edits", array);
        return body;
    }

    private static List<String> values(com.google.gson.JsonArray array) {
        List<String> out = new java.util.ArrayList<>();
        array.forEach(element -> out.add(element.getAsString()));
        return out;
    }

    // ------------------------------------------------------------------

    private DocumentStore store() {
        return new DocumentStore(temp.resolve("packs"), temp.resolve("revisions"));
    }

    private AuditLog audit() {
        return new AuditLog(temp.resolve("audit.log"));
    }

    /** The real authoring api over the store; saves reload through the hook. */
    private AuthoringApi api(DocumentStore store, AuditLog audit, CapturingTrigger trigger) {
        return new dev.materione.core.authoring.AuthoringApiImpl(store, audit,
                temp.resolve("packs"),
                new dev.materione.core.document.ModelTransactionService(
                        store, temp.resolve("packs"), audit),
                null, () -> null, trigger);
    }

    /** Session-bound actor with today's audit attribution string. */
    private static ActorContext actor(String attribution) {
        return new ActorContext(attribution, ActorContext.Kind.EDITOR_SESSION,
                java.util.Optional.empty(), node -> true);
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
    private static final class CapturingTrigger implements ReloadHook {
        private final Set<Path> files = java.util.concurrent.ConcurrentHashMap.newKeySet();

        @Override
        public void afterChange(ActorContext actor, String pack, Path changed) {
            if (!java.nio.file.Files.isDirectory(changed)) {
                files.add(changed);
            }
        }
    }
}
