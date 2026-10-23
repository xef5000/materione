package dev.materione.web.rpc.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import dev.materione.api.ApiResult;
import dev.materione.api.admin.AdminApi;
import dev.materione.api.auth.ActorContext;
import dev.materione.api.pipeline.ApplyReport;
import dev.materione.api.pipeline.PipelineApi;
import dev.materione.api.pipeline.ReloadScope;
import dev.materione.api.pipeline.ReloadSummary;
import dev.materione.core.document.AuditLog;
import dev.materione.core.document.Documents;
import dev.materione.core.settings.SettingsService;
import dev.materione.web.protocol.Wire;
import dev.materione.web.rpc.RpcDispatcher;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The control-center surface: manual scope reloads reach the trigger with the
 * right scope, the audit tail reads back what was written, and settings edits
 * are hash-guarded and validated BEFORE any bytes touch disk.
 */
class AdminOpsTest {

    @TempDir
    Path temp;

    @Test
    void scopeReloadAuditTailAndSettingsRoundTrip() throws Exception {
        AuditLog audit = new AuditLog(temp.resolve("audit.log"));
        ScopeCapturingTrigger trigger = new ScopeCapturingTrigger();
        Path settingsFile = temp.resolve("settings").resolve("settings.yml");
        RpcDispatcher dispatcher = new RpcDispatcher();
        AdminOps.register(dispatcher, admin(audit, settingsFile), trigger,
                AdminOpsTest::actor,
                (actor, target) -> audit.record(actor, "pipeline.reload", target));

        // Manual full reload: fire-and-forget — reaches the host as
        // ReloadScope.Everything, replies immediately, never blocks.
        Wire.Response reload = dispatcher.dispatch(
                request("pipeline.reload", "1", object("scope", "everything")));
        assertThat(reload.ok()).isTrue();
        assertThat(payload(reload).get("queued").getAsBoolean()).isTrue();
        assertThat(trigger.scopes).hasSize(1);
        assertThat(trigger.scopes.get(0)).isInstanceOf(ReloadScope.Everything.class);

        // Per-pack reload carries the pack id.
        dispatcher.dispatch(request("pipeline.reload", "2",
                object("scope", "one-pack", "pack", "demo")));
        assertThat(trigger.scopes.get(1)).isEqualTo(new ReloadScope.OnePack("demo"));

        // Unknown scope is a clean request error, not a crash.
        Wire.Response badScope = dispatcher.dispatch(
                request("pipeline.reload", "3", object("scope", "half")));
        assertThat(badScope.ok()).isFalse();

        // Settings read on a missing file → MTR-0009.
        Wire.Response missing = dispatcher.dispatch(request("admin.settings.get", "4"));
        assertThat(missing.error().code()).isEqualTo("MTR-0009");

        // Round trip: write defaults server-side, then read them back.
        SettingsService.write(settingsFile, SettingsService.defaults());
        String raw = Files.readString(settingsFile, StandardCharsets.UTF_8);
        Wire.Response got = dispatcher.dispatch(request("admin.settings.get", "5"));
        assertThat(got.ok()).isTrue();
        assertThat(payload(got).get("sha256").getAsString())
                .isEqualTo(Documents.sha256(raw));

        // Stale write (wrong hash) → visible conflict, nothing written.
        String edited = raw.replace("debug: false", "debug: true");
        Wire.Response stale = dispatcher.dispatch(request("admin.settings.put", "6",
                settings(edited, "0".repeat(64))));
        assertThat(stale.ok()).isFalse();
        assertThat(stale.error().code()).isEqualTo("MTR-0034");

        // Invalid draft rejected by the validator before disk — MTR-0007.
        Wire.Response invalid = dispatcher.dispatch(request("admin.settings.put", "7",
                settings("a: [broken\n", Documents.sha256(raw))));
        assertThat(invalid.ok()).isFalse();
        assertThat(invalid.error().code()).isEqualTo("MTR-0007");
        assertThat(Files.readString(settingsFile)).isEqualTo(raw);

        // Valid draft + current hash lands.
        Wire.Response applied = dispatcher.dispatch(request("admin.settings.put", "8",
                settings(edited, Documents.sha256(raw))));
        assertThat(applied.ok()).isTrue();
        assertThat(Files.readString(settingsFile)).contains("debug: true");

        String auditText = Files.readString(temp.resolve("audit.log"));
        assertThat(auditText).contains("pipeline.reload");
        assertThat(auditText).contains("admin.settings.put");

        // The audit tail reads back what record() wrote.
        Wire.Response trail = dispatcher.dispatch(request("admin.audit.recent", "9"));
        assertThat(trail.ok()).isTrue();
        assertThat(payload(trail).get("entries").toString()).contains("pipeline.reload");
    }

    // ------------------------------------------------------------------

    /** The api.pipeline() seam: records the scopes the ops ask for. */
    private static final class ScopeCapturingTrigger implements PipelineApi {
        final List<ReloadScope> scopes = new ArrayList<>();

        @Override
        public java.util.concurrent.CompletableFuture<ApiResult<ReloadSummary>> reload(
                ActorContext actor, ReloadRequest request) {
            scopes.add(request.scope());
            return java.util.concurrent.CompletableFuture.completedFuture(
                    ApiResult.ok(new ReloadSummary(false, false, 1, 0, 53, 1, null, null, null, null)));
        }

        @Override
        public java.util.Optional<ApplyReport> lastApplyReport() {
            return java.util.Optional.empty();
        }

        @Override
        public List<RestartStaged> pendingRestartChanges() {
            return List.of();
        }

        @Override
        public List<Quarantined> quarantinedResources() {
            return List.of();
        }

        @Override
        public AutoCloseable subscribeReloads(java.util.function.Consumer<ReloadEvent> listener) {
            return () -> { };
        }
    }

    /**
     * The admin surface contract the ops consume: settings are hash-guarded
     * and validated before any bytes land (the plugin's AdminApiImpl holds
     * the real implementation; this pins the wire semantics).
     */
    private AdminApi admin(AuditLog audit, Path settingsFile) {
        return new AdminApi() {
            @Override
            public DoctorReport doctor() {
                throw new UnsupportedOperationException();
            }

            @Override
            public ApiResult<SettingsView> getSettings(ActorContext actor) {
                try {
                    if (!Files.isRegularFile(settingsFile)) {
                        return ApiResult.fail(new dev.materione.api.diagnostics.Diagnostic(
                                dev.materione.api.diagnostics.DiagnosticCodes.UNRESOLVED_REFERENCE,
                                dev.materione.api.diagnostics.Severity.ERROR,
                                "settings file does not exist yet", null, null, null));
                    }
                    String raw = Files.readString(settingsFile, StandardCharsets.UTF_8);
                    return ApiResult.ok(new SettingsView(raw, Documents.sha256(raw), java.util.Map.of()));
                } catch (java.io.IOException e) {
                    return ApiResult.fail(new dev.materione.api.diagnostics.Diagnostic(
                            dev.materione.api.diagnostics.DiagnosticCodes.PIPELINE_STAGE_FAILED,
                            dev.materione.api.diagnostics.Severity.ERROR,
                            String.valueOf(e.getMessage()), null, null, null));
                }
            }

            @Override
            public ApiResult<Void> putSettings(ActorContext actor, String content, String expectedSha256) {
                List<dev.materione.api.diagnostics.Diagnostic> problems =
                        SettingsService.validateDraft(content);
                if (!problems.isEmpty()) {
                    return ApiResult.fail(problems);
                }
                try {
                    String current = Files.isRegularFile(settingsFile)
                            ? Files.readString(settingsFile, StandardCharsets.UTF_8) : "";
                    if (!Documents.sha256(current).equals(expectedSha256)) {
                        return ApiResult.fail(new dev.materione.api.diagnostics.Diagnostic(
                                dev.materione.api.diagnostics.DiagnosticCodes.EDITOR_DOCUMENT_CONFLICT,
                                dev.materione.api.diagnostics.Severity.WARN,
                                "settings changed elsewhere since you loaded them",
                                null, null, "re-read settings and re-apply your change"));
                    }
                    Files.createDirectories(settingsFile.getParent());
                    Files.writeString(settingsFile, content, StandardCharsets.UTF_8);
                } catch (java.io.IOException e) {
                    return ApiResult.fail(new dev.materione.api.diagnostics.Diagnostic(
                            dev.materione.api.diagnostics.DiagnosticCodes.PIPELINE_STAGE_FAILED,
                            dev.materione.api.diagnostics.Severity.ERROR,
                            String.valueOf(e.getMessage()), null, null, null));
                }
                audit.record(actor == null ? "test" : actor.actorId(),
                        "admin.settings.put", "settings.yml");
                return ApiResult.ok(null);
            }

            @Override
            public List<AuditEntry> recentAudit(int limit) {
                List<AuditEntry> out = new ArrayList<>();
                for (AuditLog.Entry entry : audit.recent(limit)) {
                    out.add(new AuditEntry(entry.ts(), entry.actor(), entry.op(), entry.target()));
                }
                return out;
            }

            @Override
            public EditorSessionControl editorSessions() {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static ActorContext actor(String attribution) {
        return new ActorContext(attribution, ActorContext.Kind.EDITOR_SESSION,
                java.util.Optional.empty(), node -> true);
    }

    private static JsonObject settings(String content, String sha256) {
        JsonObject object = new JsonObject();
        object.addProperty("content", content);
        object.addProperty("sha256", sha256);
        return object;
    }

    private static Wire.Request request(String op, String id) {
        return new Wire.Request(op, id, new JsonObject());
    }

    private static Wire.Request request(String op, String id, JsonObject payload) {
        return new Wire.Request(op, id, payload);
    }

    private static JsonObject payload(Wire.Response response) {
        return response.data().getAsJsonObject();
    }

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
}
