package dev.materione.plugin.runtime.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.auth.ActorContext;
import dev.materione.api.diagnostics.DiagnosticsBus;
import dev.materione.api.platform.PlatformDescriptor;
import dev.materione.api.scheduling.SchedulingGateway;
import dev.materione.core.diagnostics.DiagnosticsBusService;
import dev.materione.core.diagnostics.QuarantineTracker;
import dev.materione.core.registry.RegistryService;
import dev.materione.plugin.runtime.PipelineService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Contract tests for the runtime catalog queries (api.md §5 phase 3): the
 * catalog reads the live shadow-swapped registries, and {@code resolveItem}
 * behaves like the give command's resolver. World/media operations need a
 * server and are exercised by the headless harness instead.
 */
class RuntimeApiImplTest {

    @TempDir
    Path temp;

    private final DiagnosticsBusService diagnostics = new DiagnosticsBusService(64);
    private final QuarantineTracker quarantine = new QuarantineTracker();
    private final SchedulingGateway scheduling = new PipelineApiImplTest.DirectSchedulingGateway();

    @Test
    void catalogReflectsTheLiveApply() throws IOException {
        RuntimeApiImpl api = fixtureLoaded();

        assertThat(api.items()).extracting("id").contains(NamespacedId.parse("demo:ruby"));
        assertThat(api.item(NamespacedId.parse("demo:ruby"))).isPresent();
        assertThat(api.item(NamespacedId.parse("demo:missing"))).isEmpty();
        assertThat(api.recipes()).isNotNull();
        assertThat(api.glyphs()).isNotNull();
        assertThat(api.effects()).isNotNull();
        // pack-side types list from the content graph
        assertThat(api.sounds()).isNotNull();
        assertThat(api.paintings()).isNotNull();
    }

    @Test
    void resolveItemBehavesLikeTheGiveResolver() throws IOException {
        RuntimeApiImpl api = fixtureLoaded();

        Optional<NamespacedId> bare = api.resolveItem("ruby");
        assertThat(bare).contains(NamespacedId.parse("demo:ruby"));

        Optional<NamespacedId> full = api.resolveItem("demo:ruby");
        assertThat(full).contains(NamespacedId.parse("demo:ruby"));

        assertThat(api.resolveItem("no-such-item-anywhere")).isEmpty();
    }

    @Test
    void giveFailsSoftForOfflinePlayers() throws IOException {
        RuntimeApiImpl api = fixtureLoaded();

        var future = api.itemsOps().giveItem(
                ActorContext.console(), NamespacedId.parse("demo:ruby"),
                java.util.UUID.randomUUID(), 1);

        assertThat(future).isCompletedWithValueMatching(result -> !result.ok());
        assertThat(future.join().diagnostics().get(0).message()).contains("not online");
    }

    // ------------------------------------------------------------------

    /** Loads the demo pack (one item) into a real pipeline, like the harness corpus shape. */
    private RuntimeApiImpl fixtureLoaded() throws IOException {
        Path samplePack = temp.resolve("content/packs/demo");
        Files.createDirectories(samplePack.resolve("items"));
        Files.writeString(samplePack.resolve("pack.yml"),
                "id: demo\nversion: 1.0.0\nschema_version: 1\n");
        Files.writeString(samplePack.resolve("items/ruby.yml"),
                "ruby:\n  material: DIAMOND\n  model: demo:ruby_model\n");
        Files.createDirectories(samplePack.resolve("assets/textures"));
        Files.write(samplePack.resolve("assets/textures/ruby.png"), new byte[] {1, 2, 3});
        Files.createDirectories(samplePack.resolve("assets/models"));
        Files.writeString(samplePack.resolve("assets/models/ruby_model.json"),
                "{\"parent\": \"item/generated\"}");

        PipelineService pipeline = new PipelineService(
                null, diagnostics, quarantine, new RegistryService(), scheduling,
                new PlatformDescriptor("1.21.4", false, false, false, java.util.Set.of()));
        pipeline.registerBuiltins();
        var storage = new dev.materione.core.storage.StorageService().init(temp);
        pipeline.reload(
                new dev.materione.api.pipeline.ReloadScope.Everything(),
                storage.contentPacksDir(), storage.generatedDir(), storage.storageDir());

        return new RuntimeApiImpl(null, scheduling, pipeline, null, null);
    }
}
