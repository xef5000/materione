package dev.materione.web.rpc.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.materione.core.document.AuditLog;
import dev.materione.core.document.DocumentStore;
import dev.materione.core.pipeline.ReloadScope;
import dev.materione.web.ReloadTrigger;
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
        assertThat(trigger.sessionId).isEqualTo("session-one");
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

    @Test
    void failureAfterPromotionRestoresAssetsAndDoesNotTouchOwner() throws Exception {
        DocumentStore documents = documents();
        documents.createPack("demo", "test");
        documents.createDocument(
                "demo", "items/gem.yml", "gem:\n  material: STICK\n", "test");
        Path model = temp.resolve("packs/demo/assets/models/gem.json");
        Files.createDirectories(model.getParent());
        Files.writeString(model, "old model");
        Trigger trigger = new Trigger();
        RpcDispatcher dispatcher = new RpcDispatcher();
        AuthoringModelOps.register(
                dispatcher, documents, temp.resolve("packs"),
                new AuditLog(temp.resolve("audit.log")), trigger,
                () -> { throw new IllegalStateException("injected"); });

        Wire.Response response =
                dispatcher.dispatch(request(1, "new model"), "session-one");

        assertThat(response.ok()).isFalse();
        assertThat(Files.readString(model)).isEqualTo("old model");
        assertThat(documents.read("demo", "items/gem.yml").raw()).doesNotContain("model:");
        assertThat(trigger.scope).isNull();
    }

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

    private RpcDispatcher dispatcher(DocumentStore documents, Trigger trigger) {
        RpcDispatcher dispatcher = new RpcDispatcher();
        AuthoringModelOps.register(
                dispatcher,
                documents,
                temp.resolve("packs"),
                new AuditLog(temp.resolve("audit.log")),
                trigger);
        return dispatcher;
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

    private static final class Trigger implements ReloadTrigger {
        private ReloadScope scope;
        private String sessionId;

        @Override
        public CompletableFuture<Outcome> reloadFiles(Set<Path> files) {
            return CompletableFuture.completedFuture(Outcome.rejected());
        }

        @Override
        public CompletableFuture<Outcome> reloadScopeForPreview(
                ReloadScope scope, String sessionId) {
            this.scope = scope;
            this.sessionId = sessionId;
            return CompletableFuture.completedFuture(new Outcome(true, false, 1, 0));
        }
    }
}
