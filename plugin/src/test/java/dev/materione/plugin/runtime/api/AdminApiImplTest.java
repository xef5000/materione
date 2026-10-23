package dev.materione.plugin.runtime.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.ApiResult;
import dev.materione.api.admin.AdminApi;
import dev.materione.api.auth.ActorContext;
import dev.materione.api.platform.ProbeResult;
import dev.materione.core.diagnostics.DiagnosticsBusService;
import dev.materione.core.diagnostics.QuarantineTracker;
import dev.materione.core.document.AuditLog;
import dev.materione.core.registry.RegistryService;
import dev.materione.core.settings.SettingsService;
import dev.materione.core.storage.RestartStaging;
import dev.materione.plugin.runtime.PipelineService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Contract tests for the admin surface (api.md §5 phase 3): the doctor
 * report is structured data (one source for the command and the dashboard),
 * settings writes are hash-guarded and pre-validated, and the audit tail
 * maps one-to-one onto the API record.
 */
class AdminApiImplTest {

    @TempDir
    Path temp;

    private final DiagnosticsBusService diagnostics = new DiagnosticsBusService(64);
    private final QuarantineTracker quarantine = new QuarantineTracker();

    @Test
    void doctorIsStructuredData() throws IOException {
        AdminApi api = fixtureLoaded();

        AdminApi.DoctorReport report = api.doctor();

        assertThat(report.platform()).isNotNull();
        assertThat(report.platform().minecraftVersion()).isEqualTo("1.21.4");
        assertThat(report.liveItems()).isEqualTo(1);
        assertThat(report.quarantinedCount()).isZero();
        assertThat(report.registryLines()).isNotEmpty();
        assertThat(report.delivery().running()).isFalse();
        assertThat(report.recentDiagnostics()).isNotNull();
    }

    @Test
    void settingsAreHashGuardedAndPreValidated() throws IOException {
        AdminApi api = fixtureLoaded();

        ApiResult<AdminApi.SettingsView> current = api.getSettings(ActorContext.console());
        assertThat(current.ok()).isTrue();
        String original = current.value().content();

        // invalid draft: never reaches the disk
        ApiResult<Void> invalid = api.putSettings(
                ActorContext.console(), "version: [broken\n", current.value().sha256());
        assertThat(invalid.ok()).isFalse();
        assertThat(api.getSettings(ActorContext.console()).value().content()).isEqualTo(original);

        // stale hash: conflict, file still unchanged
        ApiResult<Void> stale = api.putSettings(
                ActorContext.console(), original, "deadbeef");
        assertThat(stale.ok()).isFalse();
        assertThat(stale.diagnostics().get(0).code().code()).isEqualTo("MTR-0034");
        assertThat(api.getSettings(ActorContext.console()).value().content()).isEqualTo(original);

        // same content, correct hash: accepted (a no-op write)
        ApiResult<Void> applied = api.putSettings(
                ActorContext.console(), original, current.value().sha256());
        assertThat(applied.ok()).isTrue();
    }

    @Test
    void auditTailMapsOneToOne() throws IOException {
        AdminApi api = fixtureLoaded();

        AdminApi.SettingsView settings = api.getSettings(ActorContext.console()).value();
        assertThat(api.putSettings(
                ActorContext.console(), settings.content(), settings.sha256()).ok()).isTrue();
        assertThat(api.recentAudit(8)).isNotEmpty();
        assertThat(api.recentAudit(8).get(0).operation()).isEqualTo("admin.settings.put");
        assertThat(api.recentAudit(8).get(0).actor()).isEqualTo("console");
    }

    @Test
    void editorStatusDefaultsToStopped() throws IOException {
        assertThat(fixtureLoaded().editorSessions().status().running()).isFalse();
    }

    // ------------------------------------------------------------------

    private AdminApi fixtureLoaded() throws IOException {
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

        var scheduling = new PipelineApiImplTest.DirectSchedulingGateway();
        // one registry service shared by the pipeline and the api, like the plugin wiring
        RegistryService registries = new RegistryService();
        PipelineService pipeline = new PipelineService(
                null, diagnostics, quarantine, registries, scheduling,
                new dev.materione.api.platform.PlatformDescriptor(
                        "1.21.4", false, false, false, java.util.Set.of()));
        pipeline.registerBuiltins();
        var storage = new dev.materione.core.storage.StorageService().init(temp);
        pipeline.reload(
                new dev.materione.api.pipeline.ReloadScope.Everything(),
                storage.contentPacksDir(), storage.generatedDir(), storage.storageDir());

        new SettingsService().load(temp.resolve("settings"));
        AuditLog audit = new AuditLog(temp.resolve("audit.log"));
        RestartStaging staging = RestartStaging.open(temp.resolve("staged-restart"));
        return new AdminApiImpl(
                () -> new ProbeResult.Supported(
                        new dev.materione.api.platform.PlatformDescriptor(
                                "1.21.4", false, false, false, java.util.Set.of())),
                registries,
                diagnostics,
                quarantine,
                () -> pipeline,
                () -> staging,
                () -> null,
                temp.resolve("settings"),
                audit,
                () -> null,
                null,
                () -> null,
                () -> null,
                () -> null);
    }
}
