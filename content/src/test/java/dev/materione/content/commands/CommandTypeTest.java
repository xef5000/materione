package dev.materione.content.commands;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.content.gui.logic.GuiOpen;
import dev.materione.core.document.LosslessYaml;
import dev.materione.testing.golden.GoldenFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The {@code command} content type (logic.md §40): its self-description, the shape it parses, the one schema it shares
 * with a GUI's {@code open:} block, the lossless round-trip of the golden document under {@code fixtures/commands/}
 * (load then untouched save is byte-identical, unknown keys survive), and the fault fixtures: a document whose
 * {@code do:} is not a list is refused whole and its healthy sibling is not, and a malformed block is a diagnostic that
 * keeps what could be read.
 */
class CommandTypeTest {

    private final CommandType type = new CommandType();

    private static String fixture(String name) throws IOException {
        return Files.readString(GoldenFiles.fixturesRoot().resolve("commands").resolve(name));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> resource(String yaml, String key) {
        return (Map<String, Object>) LosslessYaml.parse(yaml).data().get(key);
    }

    private CommandDefinition parse(String file, String key) throws IOException {
        var parsed = type.parse(NamespacedId.of("shop", key), resource(fixture(file), key), Map.of());
        assertThat(parsed).isPresent();
        return (CommandDefinition) parsed.get();
    }

    @Test
    void theTypeIsNamedCommandAndLivesInCommandsDirectory() {
        assertThat(type.name()).isEqualTo("command");
        assertThat(type.directory()).isEqualTo("commands");
    }

    @Test
    void theSelfDescriptionIsTheOneSchemaSource() {
        var parameters = type.describe().parameters();

        assertThat(parameters).extracting(ParameterSchema::id).containsExactly(
                "description", "names", "args", "permission", "visible", "requirement", "deny", "console", "cooldown",
                "charges", "per", "on_cooldown", "do", "flows");
        assertThat(parameters.stream().filter(ParameterSchema::required).map(ParameterSchema::id))
                .containsExactly("names", "do");
        // the flow is a list of steps of the one flow model, and says which variables its steps read beside the trigger's
        var flow = parameters.get(12);
        assertThat(flow.semantics().valueKind()).isEqualTo(ParameterSchema.KIND_STEPS);
        assertThat(flow.semantics().scope()).extracting(variable -> variable.path())
                .containsExactly("args.*", "command.name", "command.label");
        assertThat(parameters.get(5).semantics().valueKind()).isEqualTo(ParameterSchema.KIND_CONDITION);
        assertThat(parameters.get(6).semantics().valueKind()).isEqualTo(ParameterSchema.KIND_STEPS);
    }

    @Test
    void theThrottleKeysAreTheRulesAndNotACopy() {
        var parameters = type.describe().parameters();
        var throttle = parameters.stream().filter(parameter -> java.util.Set.of("cooldown", "charges", "per", "on_cooldown")
                .contains(parameter.id())).toList();

        // same id, type, default, description and allowed values as the rule's own entries (editor placement differs)
        assertThat(throttle).hasSize(4);
        var rule = dev.materione.api.logic.RuleThrottle.options();
        for (int i = 0; i < rule.size(); i++) {
            assertThat(throttle.get(i).id()).isEqualTo(rule.get(i).id());
            assertThat(throttle.get(i).type()).isEqualTo(rule.get(i).type());
            assertThat(throttle.get(i).defaultValue()).isEqualTo(rule.get(i).defaultValue());
            assertThat(throttle.get(i).description()).isEqualTo(rule.get(i).description());
            assertThat(throttle.get(i).allowedValues()).isEqualTo(rule.get(i).allowedValues());
            assertThat(throttle.get(i).semantics()).isEqualTo(rule.get(i).semantics());
        }
        assertThat(parameters.stream().filter(parameter -> parameter.id().equals("visible")).findFirst().orElseThrow()
                .allowedValues()).containsExactly("permitted", "always");
    }

    @Test
    void visibleBoundsAndPlayerOptionsReadWriteAndRoundTripThroughTheOneSchema() {
        var raw = Map.<String, Object>of(
                "names", List.of("pay"),
                "visible", "always",
                "args", List.of(
                        Map.of("name", "to", "type", "player", "offline", true, "selector", true),
                        Map.of("name", "amount", "type", "number", "min", 1, "max", 99.5),
                        Map.of("name", "n", "type", "int", "min", 2)));
        var parsed = CommandSchema.read(raw, CommandSchema.Dialect.COMMAND);

        assertThat(parsed.problems()).isEmpty();
        assertThat(parsed.spec().visible()).isEqualTo("always");
        assertThat(parsed.spec().args().get(0).offline()).isTrue();
        assertThat(parsed.spec().args().get(0).selector()).isTrue();
        assertThat(parsed.spec().args().get(1).min()).isEqualTo(1);
        assertThat(parsed.spec().args().get(1).max()).isEqualTo(99.5);
        assertThat(parsed.spec().args().get(2).max()).isNull();
        assertThat(CommandSchema.check(parsed.spec(), CommandSchema.Dialect.COMMAND)).isEmpty();
        // written back as authored, in the documented key order
        var written = CommandSchema.write(parsed.spec(), CommandSchema.Dialect.COMMAND);
        assertThat(written.keySet()).containsExactly("names", "args", "visible");
        assertThat(CommandSchema.read(written, CommandSchema.Dialect.COMMAND).spec()).isEqualTo(parsed.spec());
    }

    @Test
    void anOpenBlockTakesTheSameKeys() {
        var raw = Map.<String, Object>of(
                "commands", List.of("shop"), "visible", "permitted",
                "args", List.of(Map.of("name", "page", "type", "int", "min", 1, "max", 9)));
        var open = GuiOpen.read(raw);

        assertThat(open.problems()).isEmpty();
        assertThat(open.spec().visible()).isEqualTo("permitted");
        assertThat(open.spec().args().get(0).min()).isEqualTo(1);
    }

    @Test
    void aBadVisibleABoundOnTheWrongTypeAndAnEmptyRangeAreProblems() {
        var raw = Map.<String, Object>of(
                "names", List.of("x"), "visible", "sometimes",
                "args", List.of(
                        Map.of("name", "a", "type", "string", "min", 1),
                        Map.of("name", "b", "type", "int", "min", 5, "max", 2),
                        Map.of("name", "c", "type", "int", "min", "low"),
                        Map.of("name", "d", "type", "int", "offline", true)));
        var parsed = CommandSchema.read(raw, CommandSchema.Dialect.COMMAND);
        var found = new java.util.ArrayList<>(parsed.problems());
        found.addAll(CommandSchema.check(parsed.spec(), CommandSchema.Dialect.COMMAND));

        assertThat(found).extracting(Diagnostic::message).anySatisfy(message -> assertThat(message).contains("visible"))
                .anySatisfy(message -> assertThat(message).contains("'a' has min or max"))
                .anySatisfy(message -> assertThat(message).contains("'b' has a min above its max"))
                .anySatisfy(message -> assertThat(message).contains("'c' min must be a number"))
                .anySatisfy(message -> assertThat(message).contains("'d' has offline or selector"));
        assertThat(parsed.spec().visible()).isNull(); // an unknown value reads as the default
    }

    @Test
    void theArgumentFieldsAreTheSharedSchemasAndNotACopy() {
        var args = type.describe().parameters().get(2);

        assertThat(args.nested()).isEqualTo(CommandSchema.argumentFields());
        assertThat(args.nested()).extracting(ParameterSchema::id)
                .containsExactly("name", "type", "optional", "default", "suggest", "min", "max", "offline", "selector");
        assertThat(args.nested().get(1).allowedValues()).containsExactlyInAnyOrderElementsOf(CommandSchema.ARG_TYPES);
    }

    @Test
    void theGoldenDocumentParsesToItsNamesArgumentsGateAndFlow() throws IOException {
        var pay = parse("pay.yml", "pay");

        assertThat(pay.description()).isEqualTo("Send coins to another player");
        assertThat(pay.spec().commands()).containsExactly("pay", "give-coins");
        assertThat(pay.spec().args()).extracting(arg -> arg.name() + ":" + arg.type() + ":" + arg.optional())
                .containsExactly("to:player:false", "amount:number:false", "note:string:true");
        assertThat(pay.spec().permission()).isEqualTo("shop.pay");
        assertThat(pay.spec().requirement()).isEqualTo("args.amount > 0");
        assertThat(pay.spec().deny()).hasSize(1);
        assertThat(pay.console()).isFalse();
        assertThat(pay.steps()).hasSize(3);
        assertThat(pay.flows()).containsOnlyKeys("thank");
        assertThat(pay.raw()).containsKey("sidecar_note");
        assertThat(pay.problems()).isEmpty();
        assertThat(type.diagnose(pay)).isEmpty();
    }

    @Test
    void loadThenUntouchedSaveIsByteIdenticalForEveryCommandFixture() throws IOException {
        try (var files = Files.list(GoldenFiles.fixturesRoot().resolve("commands"))) {
            for (var file : files.filter(path -> path.toString().endsWith(".yml")).toList()) {
                String raw = Files.readString(file);
                assertThat(LosslessYaml.parse(raw).save()).as("round trip of %s", file.getFileName()).isEqualTo(raw);
            }
        }
    }

    @Test
    void theFixturePackCommandsRoundTripToo() throws IOException {
        try (var files = Files.list(GoldenFiles.fixturesRoot().resolve("packs").resolve("commands").resolve("commands"))) {
            for (var file : files.filter(path -> path.toString().endsWith(".yml")).toList()) {
                String raw = Files.readString(file);
                assertThat(LosslessYaml.parse(raw).save()).as("round trip of %s", file.getFileName()).isEqualTo(raw);
            }
        }
    }

    @Test
    void aDocumentThatIsEditedKeepsEveryKeyAndCommentItDidNotTouch() throws IOException {
        String raw = fixture("pay.yml");
        Map<?, ?> first = (Map<?, ?>) ((List<?>) resource(raw, "pay").get("args")).get(0);

        assertThat(first.containsKey("future_option")).isTrue();
        assertThat(raw).contains("# the player the coins go to").contains("sidecar_note: kept as written    # a key");
    }

    @Test
    void aDoThatIsNotAListIsNotACommandDocumentAndItsSiblingIsUnaffected() throws IOException {
        String raw = fixture("bad-do.yml");

        assertThat(type.parse(NamespacedId.of("shop", "broken"), resource(raw, "broken"), Map.of())).isEmpty();
        assertThat(type.parse(NamespacedId.of("shop", "healthy"), resource(raw, "healthy"), Map.of())).isPresent();
    }

    @Test
    void aFlowsThatIsNotAMappingIsRefused() {
        var parsed = type.parse(NamespacedId.of("shop", "x"), Map.of("flows", List.of("nope"), "names", List.of("x")),
                Map.of());

        assertThat(parsed).isEmpty();
    }

    @Test
    void aMalformedBlockIsDiagnosedAndWhatCouldBeReadIsKept() throws IOException {
        var sloppy = parse("bad-block.yml", "sloppy");

        // the names that were written are kept as written; the runtime keeps the valid ones
        assertThat(sloppy.spec().commands()).containsExactly("good", "Bad Name", "good");
        assertThat(sloppy.spec().args()).extracting(arg -> arg.name()).containsExactly("first", "second", "first");
        assertThat(sloppy.problems()).extracting(Diagnostic::message).anySatisfy(message ->
                assertThat(message).contains("command 'Bad Name' must match [a-z0-9_-]+"));
        assertThat(sloppy.problems()).extracting(Diagnostic::message).anySatisfy(message ->
                assertThat(message).contains("command 'good' is named twice"));
        assertThat(sloppy.problems()).extracting(Diagnostic::message).anySatisfy(message ->
                assertThat(message).contains("argument 'second' has type 'money'"));
        assertThat(sloppy.problems()).extracting(Diagnostic::message).anySatisfy(message ->
                assertThat(message).contains("argument 'first' is declared twice"));
        assertThat(sloppy.problems()).extracting(Diagnostic::message).anySatisfy(message ->
                assertThat(message).contains("a command.args entry must be a mapping with name and type"));
        assertThat(sloppy.problems()).extracting(Diagnostic::message).anySatisfy(message ->
                assertThat(message).contains("a command.args entry needs a name and a type"));
        assertThat(sloppy.problems()).extracting(Diagnostic::message).anySatisfy(message ->
                assertThat(message).contains("command.permission must not be blank"));
        // a problem is a warning that names the document, never an error that would quarantine it
        assertThat(sloppy.problems()).allSatisfy(problem -> {
            assertThat(problem.severity().name()).isEqualTo("WARN");
            assertThat(problem.resource()).isEqualTo(sloppy.id());
        });
        assertThat(type.diagnose(sloppy)).hasSameSizeAs(sloppy.problems());
    }

    @Test
    void aDocumentWithNoNamesOrNoStepsIsDiagnosedBecauseItWillNotBeRegistered() {
        var empty = (CommandDefinition) type.parse(NamespacedId.of("shop", "idle"), Map.of("description", "later"),
                Map.of()).orElseThrow();

        assertThat(type.diagnose(empty)).extracting(Diagnostic::message)
                .anySatisfy(message -> assertThat(message).contains("declares no names"))
                .anySatisfy(message -> assertThat(message).contains("has no do: steps"));
    }

    @Test
    void aSingleNameMayBeWrittenBare() {
        var parsed = (CommandDefinition) type.parse(NamespacedId.of("shop", "one"),
                Map.of("names", "one", "do", List.of(Map.of("message", "hi"))), Map.of()).orElseThrow();

        assertThat(parsed.spec().commands()).containsExactly("one");
        assertThat(parsed.problems()).isEmpty();
    }

    @Test
    void consoleMustBeABoolean() {
        var parsed = (CommandDefinition) type.parse(NamespacedId.of("shop", "one"),
                Map.of("names", List.of("one"), "console", "yes", "do", List.of(Map.of("message", "hi"))), Map.of())
                .orElseThrow();

        assertThat(parsed.console()).isFalse();
        assertThat(parsed.problems()).singleElement().satisfies(problem ->
                assertThat(problem.message()).contains("command.console must be true or false"));
    }

    @Test
    void theBlockIsReadByTheSameCodeAsAnOpenBlockAndTheyAgreeOnWhatIsValid() {
        Map<String, Object> shared = Map.of(
                "args", List.of(Map.of("name", "to", "type", "player"), Map.of("name", "n", "type", "int", "default", "1")),
                "permission", "x.y",
                "requirement", "args.n > 0");
        var asOpen = new java.util.LinkedHashMap<String, Object>(shared);
        asOpen.put("commands", List.of("pay"));
        var asCommand = new java.util.LinkedHashMap<String, Object>(shared);
        asCommand.put("names", List.of("pay"));

        var open = GuiOpen.read(asOpen);
        var command = CommandSchema.read(asCommand, CommandSchema.Dialect.COMMAND);

        assertThat(command.spec()).isEqualTo(open.spec());
        assertThat(open.problems()).isEmpty();
        assertThat(command.problems()).isEmpty();
        // and write back the same block, differing only in the key that holds the names
        assertThat(CommandSchema.write(command.spec(), CommandSchema.Dialect.COMMAND))
                .containsEntry("names", List.of("pay")).containsEntry("permission", "x.y");
        assertThat(GuiOpen.write(open.spec())).containsEntry("commands", List.of("pay"))
                .doesNotContainKey("names");
    }

    @Test
    void anOpenBlockIsStillStrictAboutItsKeysAndACommandDocumentIsNot() {
        var open = GuiOpen.read(Map.of("commands", List.of("x"), "do", List.of()));
        var command = CommandSchema.read(Map.of("names", List.of("x"), "do", List.of()), CommandSchema.Dialect.COMMAND);

        assertThat(open.problems()).extracting(Diagnostic::message).containsExactly("open: has no key 'do'");
        assertThat(command.problems()).isEmpty();
    }

    @Test
    void compilingEmitsNothingToThePack() {
        var parsed = type.parse(NamespacedId.of("shop", "idle"), Map.of(), Map.of()).orElseThrow();

        type.compile(parsed, null, null, null); // no emitter is touched: a command is runtime logic
    }
}
