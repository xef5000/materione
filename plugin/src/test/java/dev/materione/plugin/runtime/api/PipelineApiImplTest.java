package dev.materione.plugin.runtime.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.ApiResult;
import dev.materione.api.auth.ActorContext;
import dev.materione.api.pipeline.PipelineApi;
import dev.materione.api.pipeline.ReloadSummary;
import dev.materione.api.pipeline.ReloadScope;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
                        dev.materione.api.diagnostics.DiagnosticCodes.CONTENT_SCHEMA_INVALID,
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

    @Test
    void editsDuringApplyCoalesceAndFinishWithMatchingGlyphMetadataRuntimeAndArchive() throws Exception {
        try (ControlledSchedulingGateway gateway = new ControlledSchedulingGateway()) {
            Fixture fixture = fixture(gateway);
            try {
                List<PipelineApi.ReloadEvent> events = new CopyOnWriteArrayList<>();
                fixture.api.subscribeReloads(events::add);
                var initial = fixture.api.reload(ActorContext.console(), PipelineApi.ReloadRequest.everything());
                Runnable initialApply = gateway.nextApply();
                assertThat(initial).isNotDone();
                assertThat(events).isEmpty();
                assertThat(fixture.delivery.releaseStatus().publishedSha256()).isEmpty();

                Path pack = temp.resolve("content/packs/demo");
                Files.createDirectories(pack.resolve("assets/textures"));
                Files.createDirectories(pack.resolve("glyphs"));
                Files.write(pack.resolve("assets/textures/heart.png"), new byte[] {1, 2, 3});
                ActorContext editor = ActorContext.editorSession("author", UUID.randomUUID(), node -> true);
                List<CompletableFuture<ApiResult<ReloadSummary>>> edits = new ArrayList<>();
                edits.add(fixture.api.reload(editor, new PipelineApi.ReloadRequest(
                        new ReloadScope.OnePack("demo"), PipelineApi.DeliveryMode.CANDIDATE_PREVIEW)));
                Path glyph = pack.resolve("glyphs/heart.yml");
                Files.writeString(glyph, "heart:\n  texture: gui/heart\n");
                edits.add(fixture.api.reload(editor, new PipelineApi.ReloadRequest(
                        new ReloadScope.FileSet(Set.of(glyph)), PipelineApi.DeliveryMode.CANDIDATE_PREVIEW)));
                Files.writeString(glyph, "heart:\n  texture: heart\n  emoji: true\n");
                for (int i = 0; i < 50; i++) {
                    edits.add(fixture.api.reload(editor, new PipelineApi.ReloadRequest(
                            new ReloadScope.FileSet(Set.of(glyph)), PipelineApi.DeliveryMode.CANDIDATE_PREVIEW)));
                }
                assertThat(edits).allSatisfy(future -> assertThat(future).isNotDone());
                initialApply.run();
                assertThat(initial.get(10, TimeUnit.SECONDS).ok()).isTrue();

                Runnable editedApply = gateway.nextApply();
                assertThat(events).hasSize(1);
                assertThat(edits).allSatisfy(future -> assertThat(future).isNotDone());
                editedApply.run();
                for (var edit : edits) {
                    assertThat(edit.get(10, TimeUnit.SECONDS).ok()).isTrue();
                }
                assertThat(events).hasSize(2);
                assertThat(events.get(1).scope()).isEqualTo("one-pack:demo");
                assertThat(fixture.pipeline.runtime().registries()
                        .view(dev.materione.core.runtime.RuntimeGlyphs.KEY).byId("demo:heart"))
                        .isPresent();
                int codepoint = fixture.pipeline.runtime().registries()
                        .view(dev.materione.core.runtime.RuntimeGlyphs.KEY)
                        .byId("demo:heart").orElseThrow().codepoints()[0];
                Map<String, Object> metadata = new GlyphMetadataComputer(fixture.pipeline)
                        .compute("demo", "glyphs/heart.yml", Map.of("heart", Map.of("texture", "heart")));
                assertThat(metadata.toString()).contains("codepoint=" + codepoint);
                try (ZipFile zip = new ZipFile(temp.resolve("generated/pack/pack.zip").toFile())) {
                    var font = zip.getEntry("assets/demo/font/glyphs.json");
                    assertThat(font).isNotNull();
                    String json = new String(zip.getInputStream(font).readAllBytes(),
                            java.nio.charset.StandardCharsets.UTF_8);
                    assertThat(json).contains(String.valueOf((char) codepoint));
                }
                assertThat(fixture.pipeline.runtime().livePackSha256())
                        .isEqualTo(fixture.delivery.releaseStatus().candidateSha256());
                assertThat(fixture.pipeline.runtime().livePackSha256())
                        .isNotEqualTo(fixture.delivery.releaseStatus().publishedSha256());
            } finally {
                fixture.delivery.stop();
            }
        }
    }

    @Test
    void differentAuthorsAndDeliveryModesKeepSeparateOrderedRunsAndManualNoopsAnnounce() throws Exception {
        try (ControlledSchedulingGateway gateway = new ControlledSchedulingGateway()) {
            Fixture fixture = fixture(gateway);
            try {
                List<PipelineApi.ReloadEvent> events = new CopyOnWriteArrayList<>();
                fixture.api.subscribeReloads(events::add);
                ActorContext alice = ActorContext.editorSession("alice", UUID.randomUUID(), node -> true);
                ActorContext bob = ActorContext.editorSession("bob", UUID.randomUUID(), node -> true);
                var first = fixture.api.reload(alice, PipelineApi.ReloadRequest.manual(
                        new ReloadScope.OnePack("demo"), PipelineApi.DeliveryMode.CANDIDATE_PREVIEW));
                Runnable firstApply = gateway.nextApply();
                var second = fixture.api.reload(bob, PipelineApi.ReloadRequest.manual(
                        new ReloadScope.OnePack("demo"), PipelineApi.DeliveryMode.CANDIDATE_PREVIEW));
                var third = fixture.api.reload(bob, PipelineApi.ReloadRequest.manual(
                        new ReloadScope.Everything(), PipelineApi.DeliveryMode.PUBLISH_DEFAULT));
                firstApply.run();
                assertThat(first.get(10, TimeUnit.SECONDS).ok()).isTrue();
                gateway.nextApply().run();
                assertThat(second.get(10, TimeUnit.SECONDS).ok()).isTrue();
                assertThat(third).isNotDone();
                gateway.nextApply().run();
                assertThat(third.get(10, TimeUnit.SECONDS).ok()).isTrue();
                assertThat(events).hasSize(3);
                assertThat(events).allSatisfy(event -> assertThat(event.announceAlways()).isTrue());
                assertThat(events.get(1).noop()).isTrue();
                assertThat(events.get(2).noop()).isTrue();
            } finally {
                fixture.delivery.stop();
            }
        }
    }

    @Test
    void queuedScopesUnionFilesAndBroadenAcrossPacks() throws Exception {
        var workers = new ArrayDeque<Runnable>();
        SchedulingGateway gateway = new DirectSchedulingGateway() {
            @Override
            public void executeAsync(Plugin plugin, Runnable task) {
                workers.add(task);
            }
        };
        Fixture fixture = fixture(gateway);
        try {
            List<PipelineApi.ReloadEvent> events = new ArrayList<>();
            fixture.api.subscribeReloads(events::add);
            ReloadScope.FileSet ruby = new ReloadScope.FileSet(
                    Set.of(temp.resolve("content/packs/demo/items/ruby.yml")));
            ReloadScope.FileSet another = new ReloadScope.FileSet(
                    Set.of(temp.resolve("content/packs/demo/items/another.yml")));
            List<List<ReloadScope>> pairs = List.of(
                    List.of(ruby, another),
                    List.of(new ReloadScope.OnePack("demo"), new ReloadScope.OnePack("other")),
                    List.of(new ReloadScope.Everything(), ruby));
            List<String> expectedScopes = List.of("files:2", "everything", "everything");
            for (int i = 0; i < pairs.size(); i++) {
                var first = fixture.api.reload(ActorContext.console(), new PipelineApi.ReloadRequest(
                        pairs.get(i).get(0), PipelineApi.DeliveryMode.PUBLISH_DEFAULT));
                var second = fixture.api.reload(ActorContext.console(), PipelineApi.ReloadRequest.manual(
                        pairs.get(i).get(1), PipelineApi.DeliveryMode.PUBLISH_DEFAULT));
                assertThat(workers).hasSize(1);
                workers.removeFirst().run();
                assertThat(first.get(10, TimeUnit.SECONDS)).isSameAs(second.get(10, TimeUnit.SECONDS));
                assertThat(first.join().ok()).isTrue();
                assertThat(events).hasSize(i + 1);
                assertThat(events.get(i).scope()).isEqualTo(expectedScopes.get(i));
                assertThat(events.get(i).announceAlways()).isTrue();
            }
        } finally {
            fixture.delivery.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void schedulingFailuresReportFailureAndDoNotWedgeTheNextReload(boolean rejectAsync) throws Exception {
        SchedulingGateway gateway = new DirectSchedulingGateway() {
            private boolean reject = true;

            @Override
            public void executeAsync(Plugin plugin, Runnable task) {
                if (rejectAsync && reject) {
                    reject = false;
                    throw new IllegalStateException("injected async scheduling failure");
                }
                super.executeAsync(plugin, task);
            }

            @Override
            public void executeGlobal(Plugin plugin, Runnable task) {
                if (!rejectAsync && reject) {
                    reject = false;
                    throw new IllegalStateException("injected global scheduling failure");
                }
                super.executeGlobal(plugin, task);
            }
        };
        Fixture fixture = fixture(gateway);
        try {
            List<PipelineApi.ReloadEvent> events = new ArrayList<>();
            fixture.api.subscribeReloads(events::add);
            var failed = fixture.api.reload(ActorContext.console(), PipelineApi.ReloadRequest.everything())
                    .get(10, TimeUnit.SECONDS);
            assertThat(failed.ok()).isFalse();
            assertThat(failed.diagnostics().get(0).message()).contains("scheduling failure");
            assertThat(fixture.delivery.releaseStatus().publishedSha256()).isEmpty();
            var recovered = fixture.api.reload(ActorContext.console(), PipelineApi.ReloadRequest.everything())
                    .get(10, TimeUnit.SECONDS);
            assertThat(recovered.ok()).isTrue();
            assertThat(events).extracting(PipelineApi.ReloadEvent::accepted).containsExactly(false, true);
        } finally {
            fixture.delivery.stop();
        }
    }

    // ------------------------------------------------------------------

    private Fixture fixture() throws IOException {
        return fixture(scheduling);
    }

    private Fixture fixture(SchedulingGateway gateway) throws IOException {
        Path contentDir = temp.resolve("content");
        Path samplePack = contentDir.resolve("packs").resolve("demo");
        Files.createDirectories(samplePack.resolve("items"));
        Files.writeString(samplePack.resolve("pack.yml"),
                "id: demo\nversion: 1.0.0\nschema_version: 1\n");
        Files.writeString(samplePack.resolve("items/ruby.yml"),
                "ruby:\n  material: DIAMOND\n  model: demo:ruby_item\n");

        PipelineService pipeline = new PipelineService(
                null, diagnostics, quarantine, new RegistryService(), gateway,
                new dev.materione.api.platform.PlatformDescriptor(
                        "1.21.4", false, false, false, java.util.Set.of()));
        pipeline.registerBuiltins();
        DeliveryService delivery = new DeliveryService(null, diagnostics, gateway);
        delivery.start(new SettingsService().load(temp.resolve("settings")));
        var storage = new StorageService().init(temp);
        var restartStaging = RestartStaging.open(temp.resolve("staged-restart"));
        PipelineApiImpl api = new PipelineApiImpl(
                null, gateway, pipeline, quarantine, restartStaging, delivery,
                storage.contentPacksDir(), storage.generatedDir(), storage.storageDir(),
                null);
        return new Fixture(api, delivery, restartStaging, pipeline);
    }

    private record Fixture(
            PipelineApiImpl api,
            DeliveryService delivery,
            RestartStaging restartStaging,
            PipelineService pipeline) {}

    private static ExecutorService asyncExecutor() {
        return Executors.newSingleThreadExecutor();
    }

    private static void stopExecutor(ExecutorService executor) {
        executor.shutdown();
        try {
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while stopping test scheduler", e);
        } finally {
            executor.shutdownNow();
        }
    }

    private static final class ControlledSchedulingGateway extends DirectSchedulingGateway implements AutoCloseable {
        private final ExecutorService executor = asyncExecutor();
        private final BlockingQueue<Runnable> applies = new LinkedBlockingQueue<>();
        private final List<Runnable> outstanding = new ArrayList<>();
        private boolean closing;

        @Override
        public void executeAsync(Plugin plugin, Runnable task) {
            executor.execute(task);
        }

        @Override
        public synchronized void executeGlobal(Plugin plugin, Runnable task) {
            if (closing) {
                task.run();
            } else {
                outstanding.add(task);
                applies.add(task);
            }
        }

        Runnable nextApply() throws InterruptedException {
            Runnable apply = applies.poll(10, TimeUnit.SECONDS);
            assertThat(apply).as("global-thread apply was scheduled").isNotNull();
            return () -> {
                synchronized (this) {
                    outstanding.remove(apply);
                }
                apply.run();
            };
        }

        @Override
        public void close() {
            synchronized (this) {
                closing = true;
                outstanding.forEach(Runnable::run);
                outstanding.clear();
            }
            stopExecutor(executor);
        }
    }

    /** Runs everything inline — the reload path stays synchronous in tests. */
    static class DirectSchedulingGateway implements SchedulingGateway {
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
