package dev.materione.web.rpc.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.materione.core.document.AuditLog;
import dev.materione.core.document.DocumentStore;
import dev.materione.api.pipeline.ReloadScope;
import dev.materione.api.auth.ActorContext;
import dev.materione.core.authoring.ReloadHook;
import dev.materione.web.protocol.Wire;
import dev.materione.web.rpc.RpcDispatcher;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuthoringModelOpsTest {

    @TempDir
    Path temp;

    @Test
    void savesAssetsAndOwnerAsOneReload() throws Exception {
        DocumentStore documents = documents();
        documents.createPack("demo", "test");
        documents.createDocument(
                "demo", "items/gem.yml", "gem:\n  material: STICK\n", "test");
        Trigger trigger = new Trigger();
        RpcDispatcher dispatcher = dispatcher(documents, trigger);

        Wire.Response response = dispatcher.dispatch(request(1, "new model"), "session-one");

        assertThat(response.ok()).isTrue();
        assertThat(Files.readString(temp.resolve("packs/demo/assets/models/gem.json")))
                .isEqualTo("new model");
        assertThat(documents.read("demo", "items/gem.yml").raw())
                .contains("model: \"demo:gem\"");
        assertThat(Files.readString(temp.resolve("packs/demo/.materione/model-links.json")))
                .contains("\"items/gem.yml#gem.model\"")
                .contains("\"model\": \"demo:gem\"");
        assertThat(trigger.scope).isEqualTo(new ReloadScope.OnePack("demo"));
        assertThat(trigger.actorId).isEqualTo("editor:session-one");
    }

    @Test
    void staleOwnerLeavesExistingAssetsUntouched() throws Exception {
        DocumentStore documents = documents();
        documents.createPack("demo", "test");
        documents.createDocument(
                "demo", "items/gem.yml", "gem:\n  material: STICK\n", "test");
        Path model = temp.resolve("packs/demo/assets/models/gem.json");
        Files.createDirectories(model.getParent());
        Files.writeString(model, "old model");
        documents.setScalar(
                "demo", "items/gem.yml", 1, java.util.List.of("gem", "display_name"),
                "changed", "other");
        Trigger trigger = new Trigger();

        Wire.Response response = dispatcher(documents, trigger)
                .dispatch(request(1, "new model"), "session-one");

        assertThat(response.ok()).isFalse();
        assertThat(response.error().code()).isEqualTo("MTR-0034");
        assertThat(Files.readString(model)).isEqualTo("old model");
        assertThat(trigger.scope).isNull();
    }

    // "Failure after promotion restores assets" is pinned by the core test
    // ModelTransactionServiceTest — the web shell no longer owns transaction
    // internals, so the injection point lives behind the api.

    @Test
    void checkpointRestoresOwnerAndJournalAfterPostOwnerFailure() throws Exception {
        DocumentStore documents = documents();
        documents.createPack("demo", "test");
        documents.createDocument(
                "demo", "items/gem.yml", "gem:\n  material: STICK\n", "test");
        DocumentStore.Checkpoint checkpoint = documents.checkpoint("demo", "items/gem.yml");
        documents.setScalar(
                "demo", "items/gem.yml", 1, java.util.List.of("gem", "model"),
                "demo:gem", "test");

        documents.restore(checkpoint);

        assertThat(documents.read("demo", "items/gem.yml").revision()).isEqualTo(1);
        assertThat(documents.read("demo", "items/gem.yml").raw()).doesNotContain("model:");
    }

    @Test
    void createsNewOwnerAndAssetsInOneOperation() throws Exception {
        DocumentStore documents = documents();
        documents.createPack("demo", "test");
        Trigger trigger = new Trigger();
        JsonObject payload = request(1, "new model").payload();
        payload.remove("rev");
        payload.remove("field");
        payload.addProperty("ownerPath", "");
        payload.addProperty("createOwner", true);
        payload.addProperty("ownerId", "new_gem");
        payload.addProperty("ownerName", "New Gem");

        Wire.Response response = dispatcher(documents, trigger).dispatch(
                new Wire.Request("authoring.saveModel", "2", payload), "session-one");

        assertThat(response.ok()).isTrue();
        assertThat(documents.read("demo", "items/new_gem.yml").revision()).isEqualTo(1);
        assertThat(documents.read("demo", "items/new_gem.yml").raw())
                .contains("display_name: New Gem")
                .contains("model: \"demo:gem\"");
        assertThat(Files.readString(temp.resolve("packs/demo/assets/models/gem.json")))
                .isEqualTo("new model");
        assertThat(trigger.scope).isEqualTo(new ReloadScope.OnePack("demo"));
    }

    @Test
    void savesTheCachedRenderBesideTheRelationshipManifest() throws Exception {
        DocumentStore documents = documents();
        documents.createPack("demo", "test");
        documents.createDocument(
                "demo", "items/gem.yml", "gem:\n  material: STICK\n", "test");
        JsonObject payload = request(1, "new model").payload();
        JsonObject thumbnail = new JsonObject();
        thumbnail.addProperty("path", ".materione/thumbnails/gem.png");
        thumbnail.addProperty(
                "data", Base64.getEncoder().encodeToString("PNG".getBytes(StandardCharsets.UTF_8)));
        payload.getAsJsonArray("assets").add(thumbnail);

        Wire.Response response = dispatcher(documents, new Trigger())
                .dispatch(new Wire.Request("authoring.saveModel", "1", payload), "session-one");

        assertThat(response.ok()).isTrue();
        // Authoring metadata, so it must land outside assets/, which is the
        // only tree the compiler walks into the shipped client pack.
        assertThat(Files.readString(temp.resolve("packs/demo/.materione/thumbnails/gem.png")))
                .isEqualTo("PNG");
    }

    @Test
    void refusesAuthoringPathsOtherThanTheThumbnailCache() throws Exception {
        DocumentStore documents = documents();
        documents.createPack("demo", "test");
        documents.createDocument(
                "demo", "items/gem.yml", "gem:\n  material: STICK\n", "test");
        JsonObject payload = request(1, "new model").payload();
        JsonObject sneaky = new JsonObject();
        sneaky.addProperty("path", ".materione/model-links.json");
        sneaky.addProperty(
                "data", Base64.getEncoder().encodeToString("{}".getBytes(StandardCharsets.UTF_8)));
        payload.getAsJsonArray("assets").add(sneaky);

        Wire.Response response = dispatcher(documents, new Trigger())
                .dispatch(new Wire.Request("authoring.saveModel", "1", payload), "session-one");

        assertThat(response.ok()).isFalse();
        assertThat(response.error().code()).isEqualTo("MTR-0035");
        assertThat(Files.exists(temp.resolve("packs/demo/assets/models/gem.json"))).isFalse();
    }

    private RpcDispatcher dispatcher(DocumentStore documents, Trigger trigger) {
        RpcDispatcher dispatcher = new RpcDispatcher();
        var audit = new AuditLog(temp.resolve("audit.log"));
        AuthoringModelOps.register(
                dispatcher,
                new dev.materione.core.authoring.AuthoringApiImpl(documents, audit,
                        temp.resolve("packs"),
                        new dev.materione.core.document.ModelTransactionService(
                                documents, temp.resolve("packs"), audit),
                        null, () -> null, trigger),
                AuthoringModelOpsTest::actor);
        return dispatcher;
    }

    private static ActorContext actor(String attribution) {
        return new ActorContext(attribution, ActorContext.Kind.EDITOR_SESSION,
                java.util.Optional.empty(), node -> true);
    }

    private DocumentStore documents() {
        return new DocumentStore(temp.resolve("packs"), temp.resolve("revisions"));
    }

    private static Wire.Request request(int revision, String model) {
        JsonObject payload = new JsonObject();
        payload.addProperty("pack", "demo");
        payload.addProperty("ownerPath", "items/gem.yml");
        payload.addProperty("rev", revision);
        payload.addProperty("modelRef", "demo:gem");
        JsonArray field = new JsonArray();
        field.add("gem");
        field.add("model");
        payload.add("field", field);
        JsonObject asset = new JsonObject();
        asset.addProperty("path", "assets/models/gem.json");
        asset.addProperty(
                "data",
                Base64.getEncoder().encodeToString(model.getBytes(StandardCharsets.UTF_8)));
        JsonArray assets = new JsonArray();
        assets.add(asset);
        payload.add("assets", assets);
        return new Wire.Request("authoring.saveModel", "1", payload);
    }

    /** Records the pack-wide preview reload the api fires after a save. */
    private static final class Trigger implements ReloadHook {
        private ReloadScope scope;
        private String actorId;

        @Override
        public void afterChange(ActorContext actor, String pack, Path changed) {
            scope = java.nio.file.Files.isDirectory(changed)
                    ? new ReloadScope.OnePack(pack)
                    : new ReloadScope.FileSet(Set.of(changed));
            actorId = actor.actorId();
        }
    }
}
