package dev.materione.plugin.runtime.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.ApiResult;
import dev.materione.api.auth.ActorContext;
import dev.materione.api.pipeline.PipelineApi;
import dev.materione.api.pipeline.ReloadSummary;
import dev.materione.api.scheduling.SchedulingGateway;
import dev.materione.core.diagnostics.DiagnosticsBusService;
import dev.materione.core.diagnostics.QuarantineTracker;
import dev.materione.core.registry.RegistryService;
import dev.materione.core.settings.SettingsService;
import dev.materione.core.storage.RestartStaging;
import dev.materione.core.storage.StorageService;
import dev.materione.plugin.runtime.DeliveryService;
import dev.materione.plugin.runtime.PipelineService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The pipeline API against the real service composition (same fixture shape
 * as {@code EditorTransportReloadTest}): the reload future completes with
 * the summary, the event feed fires, and the operator surfaces — pending
 * restart changes and quarantine — map one-to-one onto the API records.
 */
class PipelineApiImplTest {

    @TempDir
    Path temp;

    private final DiagnosticsBusService diagnostics = new DiagnosticsBusService(64);
    private final QuarantineTracker quarantine = new QuarantineTracker();
    private final SchedulingGateway scheduling = new DirectSchedulingGateway();

    @Test
    void reloadCompletesWithTheSummaryAndFiresTheEvent() throws IOException {
        Fixture fixture = fixture();
        List<PipelineApi.ReloadEvent> events = new ArrayList<>();
        fixture.api.subscribeReloads(events::add);

        ApiResult<ReloadSummary> result = fixture.api
                .reload(ActorContext.console(), PipelineApi.ReloadRequest.everything())
                .join();

        assertThat(result.ok()).isTrue();
        assertThat(result.value().skipped()).isFalse();
        assertThat(result.value().itemsLive()).isGreaterThanOrEqualTo(0);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).accepted()).isTrue();
        assertThat(events.get(0).scope()).isEqualTo("everything");
    }

    @Test
    void targetedReloadCarriesTheFileCountScope() throws IOException {
        Fixture fixture = fixture();
        Path target = temp.resolve("content/packs/demo/items/ruby.yml");
        List<PipelineApi.ReloadEvent> events = new ArrayList<>();
        fixture.api.subscribeReloads(events::add);

        ApiResult<ReloadSummary> result = fixture.api.reload(ActorContext.console(),
                new PipelineApi.ReloadRequest(
                        new dev.materione.api.pipeline.ReloadScope.FileSet(java.util.Set.of(target)),
                        PipelineApi.DeliveryMode.PUBLISH_DEFAULT))
                .join();

        assertThat(result.ok()).isTrue();
        assertThat(events.get(0).scope()).isEqualTo("files:1");
    }

    @Test
    void pendingRestartChangesMapOneToOne() throws IOException {
        Fixture fixture = fixture();
        fixture.restartStaging.stage(
                dev.materione.api.NamespacedId.parse("demo:ruby"), "painting dimensions changed");

        List<PipelineApi.RestartStaged> pending = fixture.api.pendingRestartChanges();

        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).resource().toString()).isEqualTo("demo:ruby");
        assertThat(pending.get(0).reason()).isEqualTo("painting dimensions changed");
    }

    @Test
    void quarantinedResourcesMapOneToOne() throws IOException {
        Fixture fixture = fixture();
        quarantine.add(
                dev.materione.api.NamespacedId.parse("demo:broken"),
                dev.materione.api.diagnostics.Diagnostic.error(
                        dev.materione.core.diagnostics.DiagnosticCodes.CONTENT_SCHEMA_INVALID,
                        "width must be a number"));

        List<PipelineApi.Quarantined> quarantined = fixture.api.quarantinedResources();

        assertThat(quarantined).hasSize(1);
        assertThat(quarantined.get(0).resource().toString()).isEqualTo("demo:broken");
        assertThat(quarantined.get(0).reason().message()).contains("width");
    }

    @Test
    void candidatePreviewBindsDeliveryToTheActorsPlayer() throws IOException {
        Fixture fixture = fixture();
        UUID owner = UUID.randomUUID();
        ActorContext editor = ActorContext.editorSession("session-1", owner, node -> true);

        ApiResult<ReloadSummary> result = fixture.api.reload(editor,
                new PipelineApi.ReloadRequest(
                        new dev.materione.api.pipeline.ReloadScope.OnePack("demo"),
                        PipelineApi.DeliveryMode.CANDIDATE_PREVIEW))
                .join();

        assertThat(result.ok()).isTrue();
        // a candidate was staged for this owner (no server → no player send)
        assertThat(fixture.delivery.releaseStatus().candidateSha256()).isNotBlank();
        fixture.delivery.stop();
    }

    // ------------------------------------------------------------------

    private Fixture fixture() throws IOException {
        Path contentDir = temp.resolve("content");
        Path samplePack = contentDir.resolve("packs").resolve("demo");
        Files.createDirectories(samplePack.resolve("items"));
        Files.writeString(samplePack.resolve("pack.yml"),
                "id: demo\nversion: 1.0.0\nschema_version: 1\n");
        Files.writeString(samplePack.resolve("items/ruby.yml"),
                "ruby:\n  material: DIAMOND\n  model: demo:ruby_item\n");

        PipelineService pipeline = new PipelineService(
                null, diagnostics, quarantine, new RegistryService(), scheduling,
                new dev.materione.api.platform.PlatformDescriptor(
                        "1.21.4", false, false, false, java.util.Set.of()));
        pipeline.registerBuiltins();
        DeliveryService delivery = new DeliveryService(null, diagnostics, scheduling);
        delivery.start(new SettingsService().load(temp.resolve("settings")));
        var storage = new StorageService().init(temp);
        var restartStaging = RestartStaging.open(temp.resolve("staged-restart"));
        PipelineApiImpl api = new PipelineApiImpl(
                null, scheduling, pipeline, quarantine, restartStaging, delivery,
                storage.contentPacksDir(), storage.generatedDir(), storage.storageDir(),
                null);
        return new Fixture(api, delivery, restartStaging);
    }

    private record Fixture(
            PipelineApiImpl api,
            DeliveryService delivery,
            RestartStaging restartStaging) {}

    /** Runs everything inline — the reload path stays synchronous in tests. */
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
