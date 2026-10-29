package dev.materione.core.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.authoring.AuthoringApi;
import dev.materione.api.gui.GuiAuthoringApi;
import dev.materione.core.document.AuditLog;
import dev.materione.core.document.DocumentStore;
import dev.materione.core.document.ModelTransactionService;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The GUI surface on {@link AuthoringApi#gui()}: built once, over the instance it belongs to, and
 * failing soft where no GUI compiler is wired.
 */
class GuiSurfaceWiringTest {

    @TempDir
    Path temp;

    private AuthoringApiImpl authoring(java.util.function.Function<AuthoringApi, GuiAuthoringApi> gui) {
        Path packsRoot = temp.resolve("packs");
        DocumentStore store = new DocumentStore(packsRoot, temp.resolve("revisions"));
        AuditLog audit = new AuditLog(temp.resolve("audit.log"));
        return new AuthoringApiImpl(store, audit, packsRoot, new ModelTransactionService(store, packsRoot, audit),
                null, () -> null, (actor, pack, changed) -> { }, null, List::of, gui);
    }

    @Test
    void withoutAGuiCompilerTheSurfaceFailsSoftWithOneDiagnostic() {
        GuiAuthoringApi gui = authoring(null).gui();
        assertThat(gui.components()).isEmpty();
        var result = gui.get("demo", "guis/shop.yml");
        assertThat(result.ok()).isFalse();
        assertThat(result.diagnostics()).singleElement()
                .satisfies(diagnostic -> assertThat(diagnostic.message()).contains("not wired in this build"));
    }

    @Test
    void theFactoryRunsOnceOverTheInstanceTheSurfaceBelongsTo() {
        var calls = new AtomicInteger();
        var seen = new AtomicReference<AuthoringApi>();
        AuthoringApiImpl api = authoring(base -> {
            calls.incrementAndGet();
            seen.set(base);
            return new UnavailableGuiAuthoring();
        });
        GuiAuthoringApi first = api.gui();
        GuiAuthoringApi second = api.gui();
        assertThat(first).isSameAs(second);
        assertThat(calls.get()).isEqualTo(1);
        assertThat(seen.get()).isSameAs(api);
    }
}
