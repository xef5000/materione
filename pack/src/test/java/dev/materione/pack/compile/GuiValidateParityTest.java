package dev.materione.pack.compile;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.ApiResult;
import dev.materione.api.NamespacedId;
import dev.materione.api.authoring.AuthoringApi;
import dev.materione.api.content.ContentType;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.gui.GuiAuthoringApi;
import dev.materione.api.gui.GuiAuthoringApi.GuiValidateRequest;
import dev.materione.api.gui.GuiAuthoringApi.Saved;
import dev.materione.api.gui.GuiComponent;
import dev.materione.api.pipeline.ReloadScope;
import dev.materione.api.registry.Registry;
import dev.materione.api.registry.RegistryKeys;
import dev.materione.content.gui.GuiAuthoringApiImpl;
import dev.materione.content.gui.GuiComponents;
import dev.materione.content.gui.GuiType;
import dev.materione.core.authoring.AuthoringApiImpl;
import dev.materione.core.diagnostics.DiagnosticsBusService;
import dev.materione.core.diagnostics.QuarantineTracker;
import dev.materione.core.document.AuditLog;
import dev.materione.core.document.DocumentStore;
import dev.materione.core.document.ModelTransactionService;
import dev.materione.core.pipeline.PipelineRunner;
import dev.materione.core.registry.RegistryService;
import dev.materione.pack.allocation.AllocationTable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Validate parity (gui.md §10 #8): the GUI API's {@code validate} reports exactly the diagnostics the
 * pack compile publishes for the same file. The two run the same parse, diagnose and compile steps;
 * this test is what keeps them the same. The GUI carries every kind of problem the compiler reports:
 * an unknown component, a reserved character, a default-font run followed by smallcaps text, and a
 * slot filled twice.
 */
class GuiValidateParityTest {

    private static final String PACK = "parity";
    private static final String GUI = "guis/main.yml";
    private static final String FILE = """
            main:
              title: Shop
              rows: 3
              layers:
                - id: frame
                  type: panel
                  x: 0
                  y: 0
                  width: 176
                  height: 168
                - id: heading
                  type: text
                  x: 8
                  y: 6
                  text: "Shop <b>"
                - id: ghost
                  type: hologram
                - id: fallback
                  type: text
                  x: 8
                  y: 20
                  text: later
                  font: default
                - id: after
                  type: text
                  x: 8
                  y: 30
                  text: after default
                - id: wellbox
                  type: slots
                  region: container
                - id: sword
                  type: item
                  slot: 13
                  item: parity:sword
                - id: sword2
                  type: item
                  slot: 13
                  item: minecraft:diamond_sword
            """;

    @TempDir
    Path temp;

    private final DiagnosticsBusService diagnostics = new DiagnosticsBusService(512);

    @Test
    void validateReportsExactlyWhatThePackCompilePublishes() throws IOException {
        Path packsRoot = temp.resolve("packs");
        Path pack = packsRoot.resolve(PACK);
        Files.createDirectories(pack.resolve("guis"));
        Files.writeString(pack.resolve("pack.yml"), "id: " + PACK + "\nversion: 1.0.0\nschema_version: 1\ndepends: []\n");
        Files.writeString(pack.resolve(GUI), FILE);

        Registry<GuiComponent> components = components();
        var compiled = compileGui(packsRoot, components);

        var validated = validate(packsRoot, components);
        assertThat(validated.ok()).isTrue();
        assertThat(compiled).as("the compile reports the file's problems").isNotEmpty();
        assertThat(validated.value()).containsExactlyElementsOf(compiled);
    }

    /** The diagnostics the pack compile publishes against this GUI, in the order it raised them. */
    private List<Diagnostic> compileGui(Path packsRoot, Registry<GuiComponent> components) throws IOException {
        var quarantine = new QuarantineTracker();
        var runner = new PipelineRunner(diagnostics, quarantine);
        runner.request(new ReloadScope.Everything(), packsRoot);
        var packs = runner.lastPacks().stream().filter(p -> p.id().equals(PACK)).toList();
        Path storage = temp.resolve("storage");
        Files.createDirectories(storage);
        var allocation = AllocationTable.load(storage.resolve("allocation.bin"));
        List<ContentType> types = List.of(new GuiType(components));
        new PackCompiler().compile(packs, types, allocation, temp.resolve("generated"), diagnostics, quarantine, -1);
        NamespacedId resource = NamespacedId.of(PACK, "main");
        return diagnostics.recent(512).stream()
                .filter(diagnostic -> resource.equals(diagnostic.resource()))
                .toList();
    }

    private ApiResult<List<Diagnostic>> validate(Path packsRoot, Registry<GuiComponent> components) throws IOException {
        var store = new DocumentStore(packsRoot, temp.resolve("revisions"));
        var audit = new AuditLog(temp.resolve("audit.log"));
        Path storage = temp.resolve("storage");
        var allocation = AllocationTable.load(storage.resolve("allocation.bin"));
        AuthoringApiImpl authoring = new AuthoringApiImpl(store, audit, packsRoot,
                new ModelTransactionService(store, packsRoot, audit), null, () -> null, (actor, pack, changed) -> { },
                null, List::of,
                base -> new GuiAuthoringApiImpl(base, components, packsRoot, allocation::preview, List.of()));
        GuiAuthoringApi gui = authoring.gui();
        return gui.validate(new GuiValidateRequest(new Saved(PACK, GUI), List.of()));
    }

    private static Registry<GuiComponent> components() {
        var registry = new RegistryService().registry(RegistryKeys.GUI_COMPONENTS, GuiComponent.class);
        for (GuiComponent component : GuiComponents.builtins()) {
            registry.register(NamespacedId.of("materione", component.name()),
                    component, component.describe(), "materione");
        }
        return registry;
    }
}
