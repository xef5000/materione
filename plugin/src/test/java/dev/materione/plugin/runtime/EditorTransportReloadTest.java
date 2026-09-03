package dev.materione.plugin.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.diagnostics.DiagnosticsBus;
import dev.materione.api.platform.PlatformDescriptor;
import dev.materione.api.scheduling.SchedulingGateway;
import dev.materione.core.diagnostics.DiagnosticsBusService;
import dev.materione.core.diagnostics.QuarantineTracker;
import dev.materione.core.registry.RegistryService;
import dev.materione.core.settings.SettingsService;
import dev.materione.core.storage.StorageService;
import dev.materione.web.ReloadTrigger;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies that when the editor transport performs a reload following a change,
 * the newly compiled pack is published to the DeliveryService.
 */
class EditorTransportReloadTest {

    @TempDir
    Path temp;

    @Test
    void editorReloadPublishesNewPackToDelivery() throws IOException {
        Path contentDir = temp.resolve("content");
        Path packsRoot = contentDir.resolve("packs");
        Path samplePack = packsRoot.resolve("demo");
        Files.createDirectories(samplePack.resolve("items"));
        Files.createDirectories(samplePack.resolve("assets").resolve("textures"));
        Files.writeString(samplePack.resolve("pack.yml"), "id: demo\nversion: 1.0.0\nschema_version: 1\n");
        Files.writeString(samplePack.resolve("items/ruby.yml"),
                "ruby:\n  material: DIAMOND\n  model: demo:ruby_item\n");
        Files.write(samplePack.resolve("assets/textures/ruby.png"), new byte[] {1, 2, 3});

        DiagnosticsBus diagnostics = new DiagnosticsBusService(64);
        QuarantineTracker quarantine = new QuarantineTracker();
        RegistryService registries = new RegistryService();
        SchedulingGateway scheduling = new DirectSchedulingGateway();
        PlatformDescriptor descriptor = new PlatformDescriptor("1.21.4", false, false, false, java.util.Set.of());

        PipelineService pipeline = new PipelineService(
                null, diagnostics, quarantine, registries, scheduling, descriptor);
        pipeline.registerBuiltins();

        DeliveryService delivery = new DeliveryService(null, diagnostics, scheduling);
        var settings = new SettingsService().load(temp.resolve("settings"));
        delivery.start(settings);

        var storage = new StorageService().init(temp);

        // Initial load
        var initialSummary = pipeline.reload(
                storage.contentPacksDir(), storage.generatedDir(), storage.storageDir());
        assertThat(initialSummary.packSha256()).isNotNull();
        delivery.publish(initialSummary.packZip(), initialSummary.packSha256());
        String initialUrl = delivery.currentUrl();
        assertThat(initialUrl).isNotEmpty();

        EditorTransportService transport = new EditorTransportService(
                null, diagnostics, scheduling, pipeline, delivery);

        // Change an item definition in the pack
        Files.writeString(samplePack.resolve("items/ruby.yml"),
                "ruby:\n  material: EMERALD\n  model: demo:ruby_emerald\n");

        // Reload via file-set scope (simulates editor save)
        ReloadTrigger.Outcome outcome = transport.runReload(
                new dev.materione.core.pipeline.ReloadScope.FileSet(Set.of(samplePack.resolve("items/ruby.yml"))),
                storage, false).join();

        assertThat(outcome.accepted()).isTrue();
        assertThat(outcome.noop()).isFalse();

        // The delivery URL should have been updated with the new pack hash
        String updatedUrl = delivery.currentUrl();
        assertThat(updatedUrl).isNotEqualTo(initialUrl);

        delivery.stop();
    }

    private static final class DirectSchedulingGateway implements SchedulingGateway {
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

        @Override
        public void executeGlobal(Plugin plugin, Runnable task) {
            task.run();
        }
    }
}
