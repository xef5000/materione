package dev.materione.behavior.logic.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.ApiResult;
import dev.materione.api.NamespacedId;
import dev.materione.api.auth.ActorContext;
import dev.materione.api.authoring.AuthoringApi;
import dev.materione.api.authoring.AuthoringApi.CreateDocumentRequest;
import dev.materione.api.authoring.AuthoringApi.DocumentView;
import dev.materione.api.logic.LogicAuthoringApi;
import dev.materione.api.logic.LogicAuthoringApi.LogicDraft;
import dev.materione.api.logic.LogicAuthoringApi.LogicEditRequest;
import dev.materione.api.logic.LogicAuthoringApi.LogicView;
import dev.materione.api.logic.LogicAuthoringApi.NodePosition;
import dev.materione.api.logic.LogicAuthoringApi.StepView;
import dev.materione.api.logic.LogicEdit;
import dev.materione.api.logic.Threading;
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
 * Every {@link LogicEdit} through the real authoring API over a real document store: the typed view it
 * leaves, the refusals (with their step paths), the no-op that writes nothing, and the round trip that
 * keeps unknown keys and node positions. The compiler's own problems come back as warnings.
 */
class LogicAuthoringApiTest {

    private static final String PACK = "demo";
    private static final String PATH = "items/sword.yml";
    private static final String SEED = """
            sword:
              display_name: Sword
              material: iron_sword
              note: keep-me
              behavior:
                - id: first_rule
                  on: click:right
                  cooldown: 500
                  node: {x: 10, y: 20}
                  do:
                    - give: one
                      node: {x: 1, y: 2}
                    - if: "${player.level >= 1}"
                      then:
                        - give: two
                      else:
                        - give: three
                    - stop
              flows:
                buy:
                  - give: buy-step
                use_buy:
                  - call: buy
            """;

    @TempDir
    Path temp;

    private final ActorContext actor = ActorContext.console();
    private final List<String> ran = new ArrayList<>();
    private AuthoringApi authoring;
    private LogicAuthoringApi logic;

    @BeforeEach
    void setUp() {
        Path packsRoot = temp.resolve("packs");
        DocumentStore store = new DocumentStore(packsRoot, temp.resolve("revisions"));
        AuditLog audit = new AuditLog(temp.resolve("audit.log"));
        authoring = new AuthoringApiImpl(store, audit, packsRoot,
                new ModelTransactionService(store, packsRoot, audit), null, () -> null, (a, p, c) -> { },
                null, List::of);
        var fixture = new LogicFixture()
                .action(LogicFixture.recording("give", Threading.ANY, ran))
                .action(LogicFixture.recording("message", Threading.ANY, ran));
        logic = new LogicAuthoringApiImpl(authoring, fixture.lookup());
        authoring.createPack(actor, PACK);
        authoring.createDocument(actor, new CreateDocumentRequest(PACK, PATH, "sword", "Sword", SEED));
    }

    // ------------------------------------------------------------------
    // reads

    @Test
    void theViewCarriesRulesStepsBranchesAndNodePositions() {
        LogicView view = logic.get(PACK, PATH).value();

        assertThat(view.resourceId()).isEqualTo("sword");
        assertThat(view.revision()).isEqualTo(1);
        assertThat(view.rules()).hasSize(1);
        var rule = view.rules().get(0);
        assertThat(rule.on()).isEqualTo("click:right");
        assertThat(rule.path()).isEqualTo("behavior.0");
        assertThat(rule.options()).containsEntry("cooldown", 500).containsEntry("id", "first_rule");
        assertThat(rule.node()).isEqualTo(new NodePosition(10, 20));
        assertThat(rule.steps()).hasSize(3);

        StepView branch = rule.steps().get(1);
        assertThat(branch.kind()).isEqualTo("if");
        assertThat(branch.path()).isEqualTo("behavior.0.do.1");
        assertThat(branch.branches()).containsOnlyKeys("then", "else");
        assertThat(branch.branches().get("then").get(0).path()).isEqualTo("behavior.0.do.1.then.0");
        assertThat(rule.steps().get(0).kind()).isEqualTo("give");
        assertThat(rule.steps().get(0).node()).isEqualTo(new NodePosition(1, 2));
        assertThat(rule.steps().get(2).kind()).isEqualTo("stop");

        assertThat(view.flows()).extracting(flow -> flow.id()).containsExactly("buy", "use_buy");
    }

    @Test
    void aGetNeverWrites() {
        logic.get(PACK, PATH);
        assertThat(revision()).isEqualTo(1);
    }

    @Test
    void aResourceOutsideTheLogicFoldersIsRefused() {
        ApiResult<LogicView> result = logic.get(PACK, "content/other.yml");
        assertThat(result.ok()).isFalse();
    }

    // ------------------------------------------------------------------
    // rules

    @Test
    void addRuleInsertsARuleAtItsIndex() {
        var rule = Map.<String, Object>of("on", "click", "do", List.of(Map.of("message", "hi")));
        ApiResult<LogicView> result = edit(new LogicEdit.AddRule(0, rule));

        assertThat(result.ok()).isTrue();
        assertThat(result.value().rules()).hasSize(2);
        assertThat(result.value().rules().get(0).on()).isEqualTo("click");
        assertThat(result.value().rules().get(1).on()).isEqualTo("click:right");
    }

    @Test
    void addRuleRefusesAnUnknownTriggerAndWritesNothing() {
        ApiResult<LogicView> result = edit(new LogicEdit.AddRule(0, Map.of("on", "nosuch")));

        assertThat(result.ok()).isFalse();
        assertThat(result.diagnostics().get(0).code().code()).isEqualTo("MTR-0021");
        assertThat(result.diagnostics().get(0).target().field()).containsExactly("behavior", "0", "on");
        assertThat(revision()).isEqualTo(1);
    }

    @Test
    void addRuleRefusesAnIndexOutsideTheList() {
        ApiResult<LogicView> result = edit(new LogicEdit.AddRule(5, Map.of("on", "click")));
        assertThat(result.ok()).isFalse();
    }

    @Test
    void removeRulesRemovesEachNamedRule() {
        edit(new LogicEdit.AddRule(1, Map.of("on", "click", "do", List.of(Map.of("message", "b")))));
        ApiResult<LogicView> result = edit(new LogicEdit.RemoveRules(List.of(0)));

        assertThat(result.ok()).isTrue();
        assertThat(result.value().rules()).hasSize(1);
        assertThat(result.value().rules().get(0).on()).isEqualTo("click");
    }

    @Test
    void removeRulesRefusesAnIndexThatIsNotThere() {
        assertThat(edit(new LogicEdit.RemoveRules(List.of(3))).ok()).isFalse();
        assertThat(edit(new LogicEdit.RemoveRules(List.of(0, 0))).ok()).isFalse();
    }

    @Test
    void reorderRulesNeedsEveryIndexOnce() {
        edit(new LogicEdit.AddRule(1, Map.of("on", "click", "do", List.of(Map.of("message", "b")))));

        assertThat(edit(new LogicEdit.ReorderRules(List.of(0))).ok()).isFalse();
        assertThat(edit(new LogicEdit.ReorderRules(List.of(0, 0))).ok()).isFalse();

        ApiResult<LogicView> result = edit(new LogicEdit.ReorderRules(List.of(1, 0)));
        assertThat(result.ok()).isTrue();
        assertThat(result.value().rules().get(0).on()).isEqualTo("click");
    }

    @Test
    void patchRuleSetsAndUnsetsOptionsAndKeepsUnknownKeys() {
        ApiResult<LogicView> result = edit(new LogicEdit.PatchRule(0,
                Map.of("cooldown", 1000, "player", "true"), List.of("player")));

        assertThat(result.ok()).isTrue();
        assertThat(result.value().rules().get(0).options()).containsEntry("cooldown", 1000)
                .doesNotContainKey("player");
        assertThat(rawText()).contains("note: keep-me");
    }

    @Test
    void patchRuleRefusesTheStepsAndTheNodePosition() {
        assertThat(edit(new LogicEdit.PatchRule(0, Map.of("do", List.of()), List.of())).ok()).isFalse();
        assertThat(edit(new LogicEdit.PatchRule(0, Map.of("node", Map.of()), List.of())).ok()).isFalse();
        assertThat(edit(new LogicEdit.PatchRule(0, Map.of("on", "nosuch"), List.of())).ok()).isFalse();
    }

    // ------------------------------------------------------------------
    // steps

    @Test
    void insertStepPlacesAStepAtTheIndexNamedByThePath() {
        ApiResult<LogicView> result = edit(new LogicEdit.InsertStep("behavior.0.do.1", Map.of("give", "new")));

        assertThat(result.ok()).isTrue();
        var steps = result.value().rules().get(0).steps();
        assertThat(steps).hasSize(4);
        assertThat(steps.get(1).value()).isEqualTo("new");
        assertThat(steps.get(2).kind()).isEqualTo("if");
    }

    @Test
    void insertStepCanGoIntoABranch() {
        ApiResult<LogicView> result = edit(new LogicEdit.InsertStep("behavior.0.do.1.else.0", Map.of("message", "x")));

        assertThat(result.ok()).isTrue();
        var branch = result.value().rules().get(0).steps().get(1).branches().get("else");
        assertThat(branch).hasSize(2);
        assertThat(branch.get(0).kind()).isEqualTo("message");
    }

    @Test
    void insertStepRefusesAnUnknownKindWithItsPath() {
        ApiResult<LogicView> result = edit(new LogicEdit.InsertStep("behavior.0.do.0", Map.of("nosuch", 1)));

        assertThat(result.ok()).isFalse();
        assertThat(result.diagnostics().get(0).code().code()).isEqualTo("MTR-0057");
        assertThat(result.diagnostics().get(0).target().field()).containsExactly("behavior", "0", "do", "0");
        assertThat(result.diagnostics().get(0).message()).contains("unknown step kind 'nosuch'");
        assertThat(revision()).isEqualTo(1);
    }

    @Test
    void insertStepRefusesAStepThatNamesTwoKinds() {
        assertThat(edit(new LogicEdit.InsertStep("behavior.0.do.0", Map.of("give", "a", "message", "b"))).ok())
                .isFalse();
        assertThat(edit(new LogicEdit.InsertStep("behavior.0.do.0", Map.of("stop", true, "wait", 5))).ok())
                .isFalse();
    }

    @Test
    void insertStepRefusesAnIfWithoutItsThenList() {
        assertThat(edit(new LogicEdit.InsertStep("behavior.0.do.0", Map.of("if", "x"))).ok()).isFalse();
    }

    @Test
    void insertStepRefusesAPathThatIsNotAStepList() {
        assertThat(edit(new LogicEdit.InsertStep("behavior.0.do.0.node", Map.of("give", "x"))).ok()).isFalse();
        assertThat(edit(new LogicEdit.InsertStep("behavior.0", Map.of("give", "x"))).ok()).isFalse();
    }

    @Test
    void patchStepChangesItsParametersAndRefusesItsBranches() {
        ApiResult<LogicView> result = edit(new LogicEdit.PatchStep("behavior.0.do.0", Map.of("give", "changed"),
                List.of()));
        assertThat(result.ok()).isTrue();
        assertThat(result.value().rules().get(0).steps().get(0).value()).isEqualTo("changed");

        assertThat(edit(new LogicEdit.PatchStep("behavior.0.do.1", Map.of("then", List.of()), List.of())).ok())
                .isFalse();
        assertThat(edit(new LogicEdit.PatchStep("behavior.0.do.1", Map.of(), List.of("else"))).ok()).isFalse();
    }

    @Test
    void patchStepRefusesAChangeThatMakesTheStepUnknown() {
        assertThat(edit(new LogicEdit.PatchStep("behavior.0.do.0", Map.of("nosuch", 1), List.of("give"))).ok())
                .isFalse();
    }

    @Test
    void removeStepRemovesTheStep() {
        ApiResult<LogicView> result = edit(new LogicEdit.RemoveStep("behavior.0.do.2"));

        assertThat(result.ok()).isTrue();
        assertThat(result.value().rules().get(0).steps()).hasSize(2);
    }

    @Test
    void removeStepRefusesAStepThatIsNotThere() {
        assertThat(edit(new LogicEdit.RemoveStep("behavior.0.do.9")).ok()).isFalse();
    }

    @Test
    void moveStepTakesTheStepOutThenInsertsItWhereToNames() {
        ApiResult<LogicView> result = edit(new LogicEdit.MoveStep("behavior.0.do.0", "behavior.0.do.2"));

        assertThat(result.ok()).isTrue();
        var steps = result.value().rules().get(0).steps();
        assertThat(steps.get(0).kind()).isEqualTo("if");
        assertThat(steps.get(1).kind()).isEqualTo("stop");
        assertThat(steps.get(2).kind()).isEqualTo("give");
        assertThat(steps.get(2).node()).isEqualTo(new NodePosition(1, 2));
    }

    @Test
    void moveStepRefusesAMoveIntoItsOwnSubtree() {
        ApiResult<LogicView> result = edit(new LogicEdit.MoveStep("behavior.0.do.1", "behavior.0.do.1.then.0"));

        assertThat(result.ok()).isFalse();
        assertThat(result.diagnostics().get(0).message()).contains("own subtree");
        assertThat(result.diagnostics().get(0).target().field()).containsExactly("behavior", "0", "do", "1",
                "then", "0");
        assertThat(revision()).isEqualTo(1);
    }

    @Test
    void aRefusedMoveLeavesTheStepWhereItWas() {
        ApiResult<LogicView> result = edit(new LogicEdit.MoveStep("behavior.0.do.0", "behavior.0.do.1.nosuch.0"));

        assertThat(result.ok()).isFalse();
        assertThat(revision()).isEqualTo(1);
    }

    @Test
    void moveStepToItsOwnPositionIsANoOp() {
        ApiResult<LogicView> result = edit(new LogicEdit.MoveStep("behavior.0.do.1", "behavior.0.do.1"));

        assertThat(result.ok()).isTrue();
        assertThat(revision()).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // node positions

    @Test
    void setNodePositionWritesAndClearsTheHint() {
        ApiResult<LogicView> set = edit(new LogicEdit.SetNodePosition("behavior.0.do.1", 40.0, 50.0));
        assertThat(set.ok()).isTrue();
        assertThat(set.value().rules().get(0).steps().get(1).node()).isEqualTo(new NodePosition(40, 50));

        ApiResult<LogicView> cleared = edit(new LogicEdit.SetNodePosition("behavior.0.do.1", null, null));
        assertThat(cleared.ok()).isTrue();
        assertThat(cleared.value().rules().get(0).steps().get(1).node()).isNull();
    }

    @Test
    void setNodePositionRefusesAPartialOrNonFinitePosition() {
        assertThat(edit(new LogicEdit.SetNodePosition("behavior.0", 1.0, null)).ok()).isFalse();
        assertThat(edit(new LogicEdit.SetNodePosition("behavior.0", Double.NaN, 1.0)).ok()).isFalse();
    }

    @Test
    void setNodePositionRefusesAFlowAndAMissingNode() {
        assertThat(edit(new LogicEdit.SetNodePosition("flows.buy", 1.0, 1.0)).ok()).isFalse();
        assertThat(edit(new LogicEdit.SetNodePosition("behavior.4", 1.0, 1.0)).ok()).isFalse();
    }

    @Test
    void nodePositionsAndUnknownKeysSurviveOtherEdits() {
        edit(new LogicEdit.PatchRule(0, Map.of("cooldown", 250), List.of()));
        edit(new LogicEdit.InsertStep("behavior.0.do.0", Map.of("give", "front")));
        ApiResult<LogicView> result = edit(new LogicEdit.RemoveStep("behavior.0.do.0"));

        assertThat(result.ok()).isTrue();
        assertThat(result.value().rules().get(0).node()).isEqualTo(new NodePosition(10, 20));
        assertThat(result.value().rules().get(0).steps().get(0).node()).isEqualTo(new NodePosition(1, 2));
        assertThat(rawText()).contains("note: keep-me").contains("node:");
    }

    // ------------------------------------------------------------------
    // flows

    @Test
    void addFlowNeedsANewIdAndKnownSteps() {
        ApiResult<LogicView> added = edit(new LogicEdit.AddFlow("greet", List.of(Map.of("message", "hi"))));
        assertThat(added.ok()).isTrue();
        assertThat(added.value().flows()).extracting(flow -> flow.id()).contains("greet");

        assertThat(edit(new LogicEdit.AddFlow("greet", List.of())).ok()).isFalse();
        assertThat(edit(new LogicEdit.AddFlow("bad id", List.of())).ok()).isFalse();
        assertThat(edit(new LogicEdit.AddFlow("x", List.of(Map.of("nosuch", 1)))).ok()).isFalse();
    }

    @Test
    void removeFlowLeavesDanglingCallsAsCompileErrorsAndTheWriteStands() {
        ApiResult<LogicView> result = edit(new LogicEdit.RemoveFlow("buy"));

        assertThat(result.ok()).isTrue();
        assertThat(result.value().flows()).extracting(flow -> flow.id()).containsExactly("use_buy");
        assertThat(result.diagnostics()).extracting(diagnostic -> diagnostic.code().code()).contains("MTR-0058");
    }

    @Test
    void renameFlowRewritesEveryCallToIt() {
        ApiResult<LogicView> result = edit(new LogicEdit.RenameFlow("buy", "purchase"));

        assertThat(result.ok()).isTrue();
        assertThat(result.value().flows()).extracting(flow -> flow.id()).containsExactly("purchase", "use_buy");
        assertThat(rawText()).contains("call: purchase").doesNotContain("call: buy");
        assertThat(result.diagnostics()).isEmpty();
    }

    @Test
    void renameFlowRefusesAnExistingNameOrAMissingOne() {
        assertThat(edit(new LogicEdit.RenameFlow("buy", "use_buy")).ok()).isFalse();
        assertThat(edit(new LogicEdit.RenameFlow("nope", "x")).ok()).isFalse();
    }

    // ------------------------------------------------------------------
    // writes and conflicts

    @Test
    void anEditThatChangesNothingWritesNothing() {
        ApiResult<LogicView> result = edit(new LogicEdit.RemoveRules(List.of()));

        assertThat(result.ok()).isTrue();
        assertThat(revision()).isEqualTo(1);
    }

    @Test
    void aStaleRevisionIsRefusedWithTheConflictCode() {
        var request = new LogicEditRequest(PACK, PATH, 99, List.of(new LogicEdit.RemoveRules(List.of(0))));
        ApiResult<LogicView> result = logic.edit(actor, request);

        assertThat(result.ok()).isFalse();
        assertThat(result.diagnostics().get(0).code().code()).isEqualTo("MTR-0034");
    }

    @Test
    void aSuccessfulEditAdvancesTheRevisionOnce() {
        ApiResult<LogicView> result = edit(
                new LogicEdit.PatchRule(0, Map.of("cooldown", 1), List.of()),
                new LogicEdit.InsertStep("behavior.0.do.0", Map.of("give", "one-more")));

        assertThat(result.ok()).isTrue();
        assertThat(revision()).isEqualTo(2);
    }

    @Test
    void theCompilerProblemsOfAnAcceptedEditComeBackAsWarnings() {
        ApiResult<LogicView> result = edit(new LogicEdit.InsertStep("behavior.0.do.0", Map.of("message", "x")));
        assertThat(result.ok()).isTrue();
        assertThat(result.diagnostics()).isEmpty();
    }

    // ------------------------------------------------------------------
    // validate and instantiate

    @Test
    void validateReportsStepPathsOfADraftWithoutWriting() {
        var draft = new LogicDraft(PACK, "draft", List.of(Map.of("on", "click",
                "do", List.of(Map.of("give", "ok"), Map.of("nosuch", 1)))), Map.of(), null);

        ApiResult<List<dev.materione.api.diagnostics.Diagnostic>> result = logic.validate(draft);

        assertThat(result.ok()).isTrue();
        var problem = result.value().stream()
                .filter(diagnostic -> diagnostic.code().code().equals("MTR-0057"))
                .findFirst().orElseThrow();
        assertThat(problem.target().field()).containsExactly("behavior", "0", "do", "1");
        assertThat(revision()).isEqualTo(1);
    }

    @Test
    void validateTakesTheTriggerForRulesThatNameNone() {
        var draft = new LogicDraft(PACK, "draft", List.of(Map.of("do", List.of(Map.of("give", "ok")))), Map.of(),
                "click");

        ApiResult<List<dev.materione.api.diagnostics.Diagnostic>> result = logic.validate(draft);

        assertThat(result.value()).extracting(diagnostic -> diagnostic.code().code())
                .doesNotContain("MTR-0021");
    }

    @Test
    void validateRejectsAnInvalidPackOrId() {
        var draft = new LogicDraft("Bad Pack!", "draft", List.of(), Map.of(), null);
        assertThat(logic.validate(draft).ok()).isFalse();
    }

    @Test
    void instantiateRefusesEveryRecipeUntilTheRegistryArrives() {
        ApiResult<List<Map<String, Object>>> result = logic.instantiate(
                new LogicAuthoringApi.InstantiateRequest("pay_to_receive", Map.of()));

        assertThat(result.ok()).isFalse();
        assertThat(result.diagnostics().get(0).message()).contains("unknown recipe 'pay_to_receive'");
    }

    // ------------------------------------------------------------------
    // helpers

    /** One edit at the current revision. */
    private ApiResult<LogicView> edit(LogicEdit... edits) {
        return logic.edit(actor, new LogicEditRequest(PACK, PATH, revision(), List.of(edits)));
    }

    private int revision() {
        return document().revision();
    }

    private String rawText() {
        return document().raw();
    }

    private DocumentView document() {
        return authoring.getDocument(PACK, PATH).value();
    }
}
