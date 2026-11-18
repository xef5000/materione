package dev.materione.plugin.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.platform.PlatformDescriptor;
import dev.materione.api.scheduling.SchedulingGateway;
import dev.materione.core.diagnostics.DiagnosticsBusService;
import dev.materione.core.diagnostics.QuarantineTracker;
import dev.materione.core.registry.RegistryService;
import dev.materione.core.storage.StorageService;
import dev.materione.plugin.logic.LogicTestSupport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The pack compile checks a GUI's rules against the variables of the packs it compiles, not the previous generation's
 * (logic.md §3.2): a variable and the GUI rule that reads and sets it, arriving in the same reload, raise no MTR-0063.
 */
class GuiVariablesReloadTest {

    @TempDir
    Path temp;

    @Test
    void aGuiRuleUsingAVariableDeclaredInTheSameReloadIsNotRejected() throws IOException {
        var storage = new StorageService().init(temp);
        Path pack = storage.contentPacksDir().resolve("demo");
        Files.createDirectories(pack.resolve("variables"));
        Files.createDirectories(pack.resolve("guis"));
        Files.writeString(pack.resolve("pack.yml"), "id: demo\nversion: 1.0.0\nschema_version: 1\n");
        Files.writeString(pack.resolve("variables/kit.yml"), """
                kit_claimed:
                  scope: player
                  type: boolean
                  default: false
                """);
        Files.writeString(pack.resolve("guis/kits.yml"), """
                kits:
                  container: chest_3
                  title: Kits
                  layers:
                    - type: materione:button
                      id: starter
                      x: 70
                      y: 30
                      width: 36
                      height: 20
                  behavior:
                    - on: gui.click
                      layer: starter
                      do:
                        - set_var: {var: "demo:kit_claimed", op: set, value: true}
                """);

        var diagnostics = new DiagnosticsBusService(256);
        var registries = new RegistryService();
        LogicTestSupport.withBuiltIns(registries, List.of("zombie"));
        var pipeline = new PipelineService(null, diagnostics, new QuarantineTracker(), registries,
                new DirectSchedulingGateway(), new PlatformDescriptor("1.21.4", false, false, false, Set.of()));
        pipeline.registerBuiltins();

        pipeline.reload(storage.contentPacksDir(), storage.generatedDir(), storage.storageDir());

        assertThat(diagnostics.recent(256))
                .filteredOn(d -> d.code().equals(DiagnosticCodes.VARIABLE_UNDECLARED))
                .extracting(Diagnostic::message)
                .isEmpty();
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
