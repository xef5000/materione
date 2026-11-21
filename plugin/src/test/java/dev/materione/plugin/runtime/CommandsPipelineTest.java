package dev.materione.plugin.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.platform.PlatformDescriptor;
import dev.materione.behavior.flow.ActionStep;
import dev.materione.behavior.flow.CallStep;
import dev.materione.core.diagnostics.DiagnosticsBusService;
import dev.materione.core.diagnostics.QuarantineTracker;
import dev.materione.core.document.LosslessYaml;
import dev.materione.core.pipeline.ContentPack;
import dev.materione.core.pipeline.ContentResource;
import dev.materione.core.pipeline.PackManifest;
import dev.materione.core.registry.RegistryService;
import dev.materione.plugin.commands.RuntimeCommands;
import dev.materione.plugin.gui.GuiFixture;
import dev.materione.plugin.gui.open.ArgType;
import dev.materione.plugin.gui.open.OpenArgSpec;
import dev.materione.plugin.logic.LogicTestSupport;
import dev.materione.testing.golden.GoldenFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Commands as content from files to the live shadow (logic.md §40, D6): the {@code commands} fixture pack builds with no
 * diagnostics at all, each document's names, typed arguments, gate and flow are what the file wrote, a document whose
 * flow does not load claims no name, a requirement that does not load fails closed and says so, a path no trigger
 * provides is a warning with the step's path, and the {@code open:} sugar compiles to the same shape as the document
 * written out. Reading the file is the same code as {@code PaperCommandsIT}'s server.
 */
class CommandsPipelineTest {

    private static final Path PACK = GoldenFiles.fixturesRoot().resolve("packs").resolve("commands");

    private final RegistryService registries = new RegistryService();
    private final DiagnosticsBusService diagnostics = new DiagnosticsBusService(64);
    private final PipelineService pipeline = new PipelineService(null, diagnostics, new QuarantineTracker(),
            registries, null, new PlatformDescriptor("1.21.4", false, false, false, Set.of()));

    CommandsPipelineTest() {
        LogicTestSupport.withBuiltIns(registries, List.of("zombie", "player"));
        pipeline.registerBuiltins();
    }

    /** The shadow of command documents written as YAML: one resource per top-level key of each file. */
    private RuntimeCommands shadowOf(String... files) {
        var resources = new ArrayList<ContentResource>();
        int index = 0;
        for (String yaml : files) {
            var document = LosslessYaml.parse(yaml);
            for (String key : document.data().keySet()) {
                resources.add(new ContentResource(NamespacedId.of("commands", key), "command",
                        Path.of("commands/file" + index + ".yml"), document));
            }
            index++;
        }
        var manifest = new PackManifest("commands", "1", 1, List.of(), LosslessYaml.parse("id: commands\n"));
        return pipeline.buildShadow(List.of(new ContentPack("commands", Path.of("commands"), manifest, resources)))
                .view(RuntimeCommands.KEY);
    }

    private static String fixture(String name) throws IOException {
        return Files.readString(PACK.resolve("commands").resolve(name));
    }

    private static RuntimeCommands.Live command(RuntimeCommands view, String id) {
        return view.command(NamespacedId.of("commands", id)).orElseThrow();
    }

    @Test
    void theFixturePackBuildsWithNoDiagnosticsAndEachDocumentHoldsWhatTheFileWrote() throws IOException {
        RuntimeCommands view = shadowOf(fixture("pay.yml"), fixture("server.yml"), fixture("menu2.yml"));

        assertThat(diagnostics.recent(64)).extracting(Diagnostic::message).isEmpty();
        assertThat(view.all()).extracting(live -> live.id().value())
                .containsExactlyInAnyOrder("pay", "discord", "announce", "staff", "tune", "menu2");

        var pay = command(view, "pay");
        assertThat(pay.plan().commands()).containsExactly("pay", "give-coins");
        assertThat(pay.plan().permission()).isNull();
        assertThat(command(view, "staff").plan().permission()).isEqualTo("commands.staff");
        assertThat(pay.plan().args()).containsExactly(
                new OpenArgSpec("to", ArgType.PLAYER, false, null, List.of()),
                new OpenArgSpec("amount", ArgType.NUMBER, false, null, List.of()));
        assertThat(pay.plan().hasRequirement()).isTrue();
        assertThat(pay.plan().requirement()).isNotNull();
        assertThat(pay.plan().deny()).isNotNull();
        assertThat(pay.console()).isFalse();
        assertThat(pay.flow().on().type()).isEqualTo("command.run");
        assertThat(pay.flow().steps()).hasSize(2);
        // the second step addresses the player an argument named: #331's target, on a command's own argument
        var message = (ActionStep<?>) pay.flow().steps().get(1);
        assertThat(message.target().spec().toString()).contains("Arg[name=to]");

        assertThat(command(view, "announce").console()).isTrue();
        var tune = command(view, "tune");
        assertThat(tune.plan().args()).containsExactly(
                new OpenArgSpec("mode", ArgType.CHOICE, false, "soft", List.of("soft", "loud", "off")),
                new OpenArgSpec("loud", ArgType.BOOLEAN, true, null, List.of()),
                new OpenArgSpec("times", ArgType.INT, false, "1", List.of()));
        assertThat(tune.flow().steps()).singleElement().isInstanceOf(CallStep.class);
    }

    @Test
    void aStepThatDoesNotLoadIsSkippedAloneAtItsPathAndTheCommandStillRuns() {
        RuntimeCommands view = shadowOf("""
                hello:
                  names: [hello]
                  do:
                    - nosuchaction: 1
                    - message: "hi"
                """);

        assertThat(command(view, "hello").flow().steps()).hasSize(1);
        assertThat(diagnostics.recent(64)).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code().code()).isEqualTo("MTR-0057");
            assertThat(diagnostic.message()).contains("commands:hello").contains("do.0");
            assertThat(diagnostic.target().field()).containsExactly("do", "0");
        });
    }

    @Test
    void aCommandWhoseEveryStepFailsClaimsNoNameAndSaysSo() {
        RuntimeCommands view = shadowOf("""
                hello:
                  names: [hello]
                  do:
                    - nosuchaction: 1
                """);

        assertThat(view.all()).isEmpty();
        assertThat(view.declarations()).isEmpty();
        assertThat(diagnostics.recent(64)).extracting(Diagnostic::message)
                .anySatisfy(message -> assertThat(message).contains("has no do: step that loads, so it is not registered"));
    }

    @Test
    void aCommandWithNoUsableNameOrNoStepsIsNotLiveAndTheValidNamesOfAnotherDocumentAre() {
        RuntimeCommands view = shadowOf("""
                nameless:
                  names: ["Bad Name"]
                  do: [ {message: hi} ]
                steps:
                  names: [steps]
                partial:
                  names: [partial, "Also Bad"]
                  do: [ {message: hi} ]
                """);

        assertThat(view.all()).extracting(live -> live.id().value()).containsExactly("partial");
        assertThat(command(view, "partial").plan().commands()).containsExactly("partial");
    }

    @Test
    void aRequirementThatDoesNotLoadFailsClosedAndTheCommandReportsIt() {
        RuntimeCommands view = shadowOf("""
                gated:
                  names: [gated]
                  requirement: "this is ((( not an expression"
                  do: [ {message: hi} ]
                """);

        var plan = command(view, "gated").plan();
        assertThat(plan.hasRequirement()).isTrue();
        assertThat(plan.requirement()).isNull();
        assertThat(plan.requirementMet(null)).as("a requirement that did not load grants nothing").isFalse();
        assertThat(diagnostics.recent(64)).extracting(d -> d.code().code()).contains("MTR-0068");
        assertThat(diagnostics.recent(64)).filteredOn(d -> d.code().code().equals("MTR-0068")).singleElement()
                .satisfies(d -> assertThat(d.message()).contains("requirement does not load, so the command is refused"));
    }

    @Test
    void aPathNoTriggerProvidesIsAWarningAtTheStepsPathInTheDocument() {
        shadowOf("""
                hello:
                  names: [hello]
                  requirement: "nothere.value == 1"
                  deny: [ {message: "no ${nowhere.x}"} ]
                  do:
                    - message: "hi ${args.who} ${command.name} ${vars.local.x} ${elsewhere.y}"
                """);

        assertThat(diagnostics.recent(64)).extracting(Diagnostic::message)
                .anySatisfy(message -> assertThat(message).contains("elsewhere").contains("do.0"))
                .anySatisfy(message -> assertThat(message).contains("nothere").contains("requirement"))
                .anySatisfy(message -> assertThat(message).contains("nowhere").contains("deny.0"))
                .noneSatisfy(message -> assertThat(message).contains("'args'").contains("command.name"));
    }

    @Test
    void anOpenBlockAndTheDocumentWrittenOutCompileToOneShape() throws IOException {
        var f = new GuiFixture("commands", dev.materione.plugin.logic.support.ItemCatalog.none());
        var gui = f.gui(Files.readString(PACK.resolve("guis").resolve("menu.yml")));
        RuntimeCommands view = shadowOf(fixture("menu2.yml"));

        var opens = gui.open();
        var document = command(view, "menu2").plan();
        assertThat(opens.commands()).containsExactly("cmenu");
        assertThat(document.commands()).containsExactly("menu2");
        assertThat(document.args()).isEqualTo(opens.args());
        assertThat(document.permission()).isEqualTo(opens.permission());
        assertThat(document.hasRequirement()).isEqualTo(opens.hasRequirement());
        assertThat(document.deny() == null).isEqualTo(opens.deny() == null);
        assertThat(diagnostics.recent(64)).extracting(Diagnostic::message).isEmpty();
        // and the declarations the tree claims names from are the two kinds of one record
        assertThat(view.declarations()).singleElement().satisfies(declaration -> {
            assertThat(declaration.kind().name()).isEqualTo("COMMAND");
            assertThat(declaration.plan()).isSameAs(document);
        });
    }

    @Test
    void anEmptyShadowHasAnEmptyCommandViewSoTheTreeClaimsNothing() {
        RuntimeCommands view = shadowOf("""
                idle:
                  description: later
                """);

        assertThat(view.all()).isEmpty();
        assertThat(RuntimeCommands.declarations(null)).isEmpty();
    }
}
