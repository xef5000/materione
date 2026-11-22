package dev.materione.plugin.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.platform.PlatformDescriptor;
import dev.materione.core.diagnostics.DiagnosticsBusService;
import dev.materione.core.diagnostics.QuarantineTracker;
import dev.materione.core.document.LosslessYaml;
import dev.materione.core.pipeline.ContentPack;
import dev.materione.core.pipeline.ContentResource;
import dev.materione.core.pipeline.PackManifest;
import dev.materione.core.registry.RegistryService;
import dev.materione.core.runtime.RuntimeRegistries;
import dev.materione.plugin.logic.LogicTestSupport;
import dev.materione.plugin.runtime.behavior.ItemBehaviors;
import dev.materione.testing.golden.GoldenFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The protection conditions and the verbs they guard, from files to the live shadow (logic.md §43, D6: one pipeline): the
 * {@code protection} fixture pack uses {@code can_build}, {@code can_break}, {@code break_blocks} and {@code set_block} with
 * {@code as_player: false}, and loads with no diagnostics at all. {@code PaperProtectionIT}'s server reads the same files.
 */
class ProtectionPipelineTest {

    private static final Path PACK = GoldenFiles.fixturesRoot().resolve("packs").resolve("protection");

    private final RegistryService registries = new RegistryService();
    private final DiagnosticsBusService diagnostics = new DiagnosticsBusService(64);
    private final PipelineService pipeline = new PipelineService(null, diagnostics, new QuarantineTracker(),
            registries, null, new PlatformDescriptor("1.21.4", false, false, false, Set.of()));

    ProtectionPipelineTest() {
        LogicTestSupport.withBuiltIns(registries, List.of("zombie", "husk", "player"));
        pipeline.registerBuiltins();
    }

    private static ContentResource file(String relative, String type, String id) throws IOException {
        Path file = PACK.resolve(relative);
        return new ContentResource(NamespacedId.of("protection", id), type, file,
                LosslessYaml.parse(Files.readString(file)));
    }

    @Test
    void theFixturePackLoadsWithNoDiagnostics() throws IOException {
        var manifest = new PackManifest("protection", "1", 1, List.of(), LosslessYaml.parse("id: protection\n"));
        RuntimeRegistries shadow = pipeline.buildShadow(List.of(new ContentPack("protection", Path.of("protection"),
                manifest, List.of(file("items/port_pickaxe.yml", "item", "port_pickaxe"),
                        file("rules/protection.yml", "rule", "protection")))));

        assertThat(diagnostics.recent(64)).extracting(Diagnostic::message).isEmpty();
        assertThat(shadow.view(ItemBehaviors.KEY)).isNotNull();
    }
}
