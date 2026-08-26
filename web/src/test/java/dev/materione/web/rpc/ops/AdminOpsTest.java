package dev.materione.web.rpc.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import dev.materione.core.document.AuditLog;
import dev.materione.core.document.Documents;
import dev.materione.core.pipeline.ReloadScope;
import dev.materione.core.settings.SettingsService;
import dev.materione.web.ReloadTrigger;
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
        AdminOps.register(dispatcher, audit, trigger, settingsFile,
                SettingsService::validateDraft);

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

    /** Records scopes so assertions can pin exactly what the host received. */
    private static final class ScopeCapturingTrigger implements ReloadTrigger {
        final List<ReloadScope> scopes = new ArrayList<>();

        @Override
        public CompletableFuture<Outcome> reloadFiles(Set<Path> paths) {
            scopes.add(new ReloadScope.FileSet(paths));
            return CompletableFuture.completedFuture(new Outcome(true, false, 1, 0));
        }

        @Override
        public CompletableFuture<Outcome> reloadScope(ReloadScope scope) {
            scopes.add(scope);
            return CompletableFuture.completedFuture(new Outcome(true, false, 53, 0));
        }
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
