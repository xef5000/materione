package dev.materione.behavior.logic.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.ApiResult;
import dev.materione.api.NamespacedId;
import dev.materione.api.auth.ActorContext;
import dev.materione.api.authoring.AuthoringApi;
import dev.materione.api.authoring.AuthoringApi.CreateDocumentRequest;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.logic.ContextVariable;
import dev.materione.api.logic.LogicAuthoringApi;
import dev.materione.api.logic.LogicAuthoringApi.LogicEditRequest;
import dev.materione.api.logic.LogicAuthoringApi.LogicView;
import dev.materione.api.logic.LogicEdit;
import dev.materione.api.logic.Threading;
import dev.materione.api.logic.TriggerDescription;
import dev.materione.api.logic.TriggerType;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.RegistryKeys;
import dev.materione.api.registry.SelfDescription;
import dev.materione.behavior.flow.FlowCompiler;
import dev.materione.behavior.logic.LogicFixture;
import dev.materione.core.authoring.AuthoringApiImpl;
import dev.materione.core.document.AuditLog;
import dev.materione.core.document.DocumentStore;
import dev.materione.core.document.ModelTransactionService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A command document through the logic API (logic.md §40): its flow is the top-level {@code do:}, shown as the one rule of
 * the {@code command.run} trigger and edited at {@code do.<i>}, with named flows as for any host; the rule edits do not
 * apply to it; and the compile it reports is the runtime's, with the paths the author wrote. Also the flow compiler's
 * {@code document} entry point itself, and the one engine change the command needed.
 */
class CommandDocumentLogicTest {

    private static final String PACK = "demo";
    private static final String PATH = "commands/pay.yml";
    private static final String SEED = """
            pay:
              description: Pay  # kept
              future_key: keep-me
              names: [pay]
              args:
                - {name: to, type: player}
              do:
                - give: first
                - if: "${args.n > 1}"
                  then:
                    - give: second
                - call: thank
              flows:
                thank:
                  - give: thanks
            """;

    @TempDir
    Path temp;

    private final ActorContext actor = ActorContext.console();
    private AuthoringApi authoring;
    private LogicAuthoringApi logic;
    private LogicFixture fixture;

    @BeforeEach
    void setUp() {
        Path packsRoot = temp.resolve("packs");
        DocumentStore store = new DocumentStore(packsRoot, temp.resolve("revisions"));
        AuditLog audit = new AuditLog(temp.resolve("audit.log"));
        authoring = new AuthoringApiImpl(store, audit, packsRoot,
                new ModelTransactionService(store, packsRoot, audit), null, () -> null, (a, p, c) -> { },
                null, List::of);
        fixture = new LogicFixture().action(LogicFixture.recording("give", Threading.ANY, new ArrayList<>()));
        fixture.registries().registry(RegistryKeys.TRIGGERS, TriggerType.class).register(
                NamespacedId.of("materione", "command.run"), commandRun(), SelfDescription.of("Command run", "x"), "test");
        logic = new LogicAuthoringApiImpl(authoring, fixture.lookup());
        authoring.createPack(actor, PACK);
        authoring.createDocument(actor, new CreateDocumentRequest(PACK, PATH, "pay", "Pay", SEED));
    }

    private static TriggerType commandRun() {
        var description = new TriggerDescription(SelfDescription.of("Command run", "A command runs"), List.of(),
                List.of("command"), List.of(),
                List.of(ContextVariable.of("player.name", ParameterType.STRING, "The sender"),
                        ContextVariable.of("args.*", ParameterType.RAW, "The typed arguments"),
                        ContextVariable.of("command.name", ParameterType.STRING, "The name")),
                false);
        return new TriggerType() {
            @Override
            public String id() {
                return "command.run";
            }

            @Override
            public TriggerDescription describe() {
                return description;
            }
        };
    }

    @Test
    void theViewShowsTheDoFlowAsTheOneRuleOfTheCommandTriggerAndTheNamedFlows() {
        LogicView view = logic.get(PACK, PATH).value();

        assertThat(view.rules()).singleElement().satisfies(rule -> {
            assertThat(rule.on()).isEqualTo("command.run");
            assertThat(rule.path()).isEqualTo("do");
            assertThat(rule.steps()).extracting(step -> step.path()).containsExactly("do.0", "do.1", "do.2");
            assertThat(rule.steps().get(1).branches().get("then")).extracting(step -> step.path())
                    .containsExactly("do.1.then.0");
        });
        assertThat(view.flows()).singleElement().satisfies(flow -> assertThat(flow.id()).isEqualTo("thank"));
    }

    @Test
    void stepEditsAddressDoAndNamedFlowsAndKeepEveryOtherKey() {
        LogicView before = logic.get(PACK, PATH).value();
        ApiResult<LogicView> edited = logic.edit(actor, new LogicEditRequest(PACK, PATH, before.revision(), List.of(
                new LogicEdit.InsertStep("do.1", Map.of("give", "inserted")),
                new LogicEdit.PatchStep("do.0", Map.of("chance", 0.5), List.of()),
                new LogicEdit.MoveStep("do.3", "do.0"),
                new LogicEdit.AddFlow("extra", List.of(Map.of("give", "x"))),
                new LogicEdit.RenameFlow("thank", "gratitude"))));

        assertThat(edited.ok()).as("%s", edited.diagnostics()).isTrue();
        String raw = authoring.getDocument(PACK, PATH).value().raw();
        assertThat(raw).contains("description: Pay  # kept").contains("future_key: keep-me")
                .contains("names: [pay]").contains("args:").contains("inserted").contains("extra:")
                .contains("gratitude:").contains("call: gratitude").doesNotContain("behavior:");
        assertThat(edited.value().rules().get(0).steps()).hasSize(4);
        assertThat(edited.value().rules().get(0).steps().get(0).kind()).isEqualTo("call");
    }

    @Test
    void theRuleEditsDoNotApplyToACommandDocumentAndTheRefusalPointsAtTheSteps() {
        int revision = logic.get(PACK, PATH).value().revision();
        for (LogicEdit edit : List.<LogicEdit>of(
                new LogicEdit.AddRule(0, Map.of("on", "command.run", "do", List.of(Map.of("give", "x")))),
                new LogicEdit.RemoveRules(List.of(0)),
                new LogicEdit.ReorderRules(List.of(0)),
                new LogicEdit.PatchRule(0, Map.of("cooldown", 5), List.of()))) {
            ApiResult<LogicView> refused = logic.edit(actor, new LogicEditRequest(PACK, PATH, revision, List.of(edit)));

            assertThat(refused.ok()).as("%s", edit).isFalse();
            assertThat(refused.diagnostics()).singleElement().satisfies(diagnostic ->
                    assertThat(diagnostic.message()).contains("a command document has one flow, do:"));
        }
        assertThat(authoring.getDocument(PACK, PATH).value().raw()).doesNotContain("behavior:");
    }

    @Test
    void anEditThatChangesNothingWritesNothing() {
        int revision = logic.get(PACK, PATH).value().revision();

        ApiResult<LogicView> same = logic.edit(actor, new LogicEditRequest(PACK, PATH, revision, List.of(
                new LogicEdit.MoveStep("do.0", "do.0"))));

        assertThat(same.ok()).as("%s", same.diagnostics()).isTrue();
        assertThat(authoring.getDocument(PACK, PATH).value().revision()).isEqualTo(revision);
    }

    @Test
    void theProblemsAnEditReportsAreTheRuntimesWithThePathsOfTheFile() {
        int revision = logic.get(PACK, PATH).value().revision();

        ApiResult<LogicView> edited = logic.edit(actor, new LogicEditRequest(PACK, PATH, revision, List.of(
                new LogicEdit.InsertStep("do.0", Map.of("give", "reject")))));

        assertThat(edited.ok()).isTrue();
        // a compile problem names the step in do, never a behavior.<n> the file does not have
        assertThat(edited.diagnostics()).extracting(Diagnostic::message).isNotEmpty()
                .anySatisfy(message -> assertThat(message).contains("at do.0"))
                .allSatisfy(message -> assertThat(message).doesNotContain("behavior."));
    }

    @Test
    void documentCompilesDoAsOneRuleOfTheTriggerWithTheNamedFlowsAndTheAuthorsPaths() {
        var owner = NamespacedId.of(PACK, "pay");
        var steps = List.<Object>of(Map.of("give", "a"), Map.of("call", "thank"), Map.of("give", "reject"));
        var parsed = FlowCompiler.document(owner, "command.run", steps, Map.of("thank", List.of(Map.of("give", "t"))),
                fixture.lookup());

        // the step that does not load is skipped alone, at its own path in the document; its siblings load
        assertThat(parsed.rules()).singleElement().satisfies(rule -> {
            assertThat(rule.on().type()).isEqualTo("command.run");
            assertThat(rule.path().toString()).isEmpty();
            assertThat(rule.steps()).hasSize(2);
        });
        assertThat(parsed.problems()).extracting(Diagnostic::message)
                .anySatisfy(message -> assertThat(message).contains("at do.2"))
                .noneMatch(message -> message.contains("behavior."));
    }

    @Test
    void aNamedFlowThatDoesNotLoadIsReportedAtItsPathAndTheCommandThatCallsItIsSkipped() {
        var owner = NamespacedId.of(PACK, "pay");
        var parsed = FlowCompiler.document(owner, "command.run", List.of(Map.of("call", "thank")),
                Map.of("thank", List.of(Map.of("nope", 1))), fixture.lookup());

        assertThat(parsed.rules()).isEmpty();
        assertThat(parsed.problems()).extracting(Diagnostic::message)
                .anySatisfy(message -> assertThat(message).contains("flows.thank"));
    }

    @Test
    void documentWithNoStepsThatLoadReturnsNoRuleAndSaysWhy() {
        var owner = NamespacedId.of(PACK, "pay");
        var parsed = FlowCompiler.document(owner, "command.run", List.of(Map.of("nope", 1)), null, fixture.lookup());

        assertThat(parsed.rules()).isEmpty();
        assertThat(parsed.problems()).isNotEmpty();
    }
}
