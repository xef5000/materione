package dev.materione.plugin.logic.action.view;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.Facets;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.ParseRejection;
import dev.materione.api.logic.variable.CompileEnvironment;
import dev.materione.api.logic.variable.VariableDeclaration;
import dev.materione.api.logic.variable.VariableScope;
import dev.materione.behavior.logic.EngineExpressions;
import dev.materione.behavior.logic.MapLogicContext;
import dev.materione.plugin.gui.GuiFixture;
import dev.materione.plugin.gui.event.GuiContexts;
import dev.materione.plugin.view.CloseReason;
import dev.materione.plugin.view.ReadKey;
import dev.materione.plugin.view.ReadRole;
import dev.materione.plugin.view.ReadsIndex;
import dev.materione.plugin.view.ViewReads;
import dev.materione.plugin.view.ViewRef;
import dev.materione.plugin.view.ViewerRecord;
import dev.materione.plugin.view.open.OpenRequest;
import java.util.EnumSet;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code view.refresh} (views.md §3.6) against the real GUI runtime: the three selectors, the stale-record failure the
 * shipped {@code gui.refresh} had under its new name, the parse-time refusals, and the thread rule (a refresh from the
 * viewer's own thread renders at once; from another it is an immediate wake that renders once).
 */
class RefreshViewActionTest {

    private static final String PLAIN = "%s:\n  title: Plain\n  container: chest_3\n  layers: []\n";

    private GuiFixture f;
    private ViewerRecord menu;
    private RefreshViewAction action;
    private VariableDeclaration coins;
    private VariableDeclaration season;
    private VariableDeclaration tab;

    @BeforeEach
    void setUp() {
        f = new GuiFixture();
        coins = f.variables.declare("demo", "coins", VariableScope.PLAYER, 0d);
        season = f.variables.declare("demo", "season", VariableScope.SERVER, "spring");
        tab = f.declareSession("tab", "a");
        f.gui(PLAIN.formatted("menu"));
        f.gui(PLAIN.formatted("shop"));
        menu = f.opener.open(OpenRequest.of(f.viewer, GuiFixture.id("menu"), Map.of(), OpenRequest.Cause.API)).record();
        f.windows.log.clear();
        action = new RefreshViewAction(f.bridge.view());
    }

    private ParamReader reader(Map<String, Object> params) {
        return ParamReader.of(params, EngineExpressions.INSTANCE,
                CompileEnvironment.of("demo", id -> f.variables.service().declaration(id)));
    }

    private ActionOutcome run(Map<String, Object> params, MapLogicContext context) {
        return action.execute(action.parse(reader(params)), context);
    }

    private MapLogicContext inMenu() {
        return GuiContexts.build(f.viewer, menu, Map.of());
    }

    private void reads(VariableDeclaration variable) {
        f.reads = ReadsIndex.of(Map.of(new ViewRef("gui", GuiFixture.id("menu")),
                new ViewReads(Map.of(new ReadKey(variable.scope(), variable.id()), EnumSet.of(ReadRole.CONTENT)), false)));
    }

    @Test
    void withNoSelectorTheRunsOwnWindowIsRewrittenInFull() {
        assertThat(run(Map.of(), inMenu())).isEqualTo(ActionOutcome.OK);

        assertThat(f.windows.log).containsExactly("rewrite menu");
    }

    @Test
    void withNoSelectorAStaleRecordFailsNoOpenView() {
        MapLogicContext context = inMenu().with(Facets.PLAYER, f.player);
        f.sessions.end(menu, CloseReason.PLAYER);

        assertThat(run(Map.of(), context)).isEqualTo(ActionOutcome.failed("no open view"));
        assertThat(f.windows.log).isEmpty();
    }

    @Test
    void withNoSelectorARunReplacedBehindItsBackIsNoOpenViewToo() {
        f.sessions.begin(dev.materione.plugin.gui.session.GuiViewerState.record(
                f.viewer.id(), "Probe", GuiFixture.id("shop"), Map.of()));

        assertThat(run(Map.of(), inMenu())).isEqualTo(ActionOutcome.failed("no open view"));
    }

    @Test
    void aRunWithoutAPlayerHasNoViewToRefresh() {
        assertThat(run(Map.of(), new MapLogicContext(Map.of())))
                .isEqualTo(ActionOutcome.failed("event has no player"));
    }

    @Test
    void anUnboundBridgeFailsSoft() {
        var unbound = new RefreshViewAction(new dev.materione.plugin.gui.GuiBridge().view());

        assertThat(unbound.execute(unbound.parse(reader(Map.of("view", "menu"))), inMenu()))
                .isEqualTo(ActionOutcome.failed("the view runtime is not available"));
    }

    @Test
    void aViewSelectorRefreshesEveryViewerOfThatDefinitionFromAnyRun() {
        // a run with no view of its own (an item rule, the console) still names the definition
        var outside = new MapLogicContext(Map.of());

        assertThat(run(Map.of("view", "demo:menu"), outside)).isEqualTo(ActionOutcome.OK);
        assertThat(f.windows.log).containsExactly("rewrite menu");
    }

    @Test
    void aBareViewNameMeansTheNameBesideTheRunsOwnView() {
        assertThat(run(Map.of("view", "menu"), inMenu())).isEqualTo(ActionOutcome.OK);
        assertThat(f.windows.log).containsExactly("rewrite menu");
    }

    @Test
    void aViewNobodyHasOpenIsOkAndRendersNothing() {
        assertThat(run(Map.of("view", "demo:shop"), inMenu())).isEqualTo(ActionOutcome.OK);
        assertThat(f.windows.log).isEmpty();
    }

    @Test
    void aViewThatDoesNotExistFailsWithTheName() {
        assertThat(run(Map.of("view", "ghost"), inMenu()))
                .isEqualTo(ActionOutcome.failed("no live view matches 'ghost'"));
    }

    @Test
    void aKindThatDoesNotExistFailsWithTheKinds() {
        ActionOutcome outcome = run(Map.of("view", "menu", "kind", "dialog"), inMenu());

        assertThat(outcome).isInstanceOf(ActionOutcome.Failed.class);
        assertThat(((ActionOutcome.Failed) outcome).reason()).startsWith("no view kind 'dialog'");
    }

    @Test
    void aVariableSelectorRefreshesTheViewersWhoseViewReadsIt() {
        reads(season);

        assertThat(run(Map.of("var", "server.demo.season"), inMenu())).isEqualTo(ActionOutcome.OK);
        assertThat(f.windows.log).containsExactly("rewrite menu");
    }

    @Test
    void aPlayerVariableDefaultsToTheRunsPlayerAndNamesAnotherWithHolder() {
        reads(coins);
        var ctx = inMenu().with(Facets.PLAYER, f.player);

        run(Map.of("var", "player.coins"), ctx);
        assertThat(f.windows.log).as("the run's own player").containsExactly("rewrite menu");

        f.windows.log.clear();
        run(Map.of("var", "player.coins", "holder", "probe"), ctx);
        assertThat(f.windows.log).as("by name, case-insensitive").containsExactly("rewrite menu");

        f.windows.log.clear();
        run(Map.of("var", "player.coins", "holder", f.viewer.id().toString()), ctx);
        assertThat(f.windows.log).as("by UUID").containsExactly("rewrite menu");

        f.windows.log.clear();
        assertThat(run(Map.of("var", "player.coins", "holder", "Nobody"), ctx)).isEqualTo(ActionOutcome.OK);
        assertThat(f.windows.log).as("a name with no open view selects nobody").isEmpty();
    }

    @Test
    void aVariableNoViewReadsSelectsNobodyAndIsOk() {
        assertThat(run(Map.of("var", "server.demo.season"), inMenu())).isEqualTo(ActionOutcome.OK);
        assertThat(f.windows.log).isEmpty();
    }

    @Test
    void aSessionVariableIsTheRunsOwnViewsAndNeedsOne() {
        reads(tab);

        assertThat(run(Map.of("var", "session.tab"), inMenu())).isEqualTo(ActionOutcome.OK);
        assertThat(f.windows.log).containsExactly("rewrite menu");
        assertThat(run(Map.of("var", "session.tab"), new MapLogicContext(Map.of())))
                .isEqualTo(ActionOutcome.failed("no open view holds this session variable"));
    }

    @Test
    void aPlayerVariableWithNoPlayerAndNoHolderFails() {
        reads(coins);

        assertThat(run(Map.of("var", "player.coins"), new MapLogicContext(Map.of())))
                .isEqualTo(ActionOutcome.failed("event has no player; name one with holder:"));
    }

    @Test
    void fromAnotherThreadTheRefreshIsOneImmediateWakeAndRendersOnce() {
        f.ownerThread = false;

        assertThat(run(Map.of(), inMenu())).isEqualTo(ActionOutcome.OK);
        assertThat(f.windows.log).as("nothing rendered on the caller's thread").isEmpty();

        f.runWakes();
        assertThat(f.windows.log).containsExactly("rewrite menu");
    }

    @Test
    void anExplicitRefreshWhileAWakeIsQueuedRendersOnceAndTheWakeIsANoOp() {
        // the write observer is not wired here: mark the record as a write would, queue its wake, refresh, then run it
        menu.markDirty();
        f.scheduler.wake(menu);
        assertThat(f.wakes).hasSize(1);

        run(Map.of(), inMenu());
        f.runWakes();

        assertThat(f.windows.log).containsExactly("rewrite menu");
    }

    @Test
    void theSelectorsAreChecked() {
        assertThatThrownBy(() -> action.parse(reader(Map.of("view", "menu", "var", "server.demo.season"))))
                .hasMessageContaining("one selector");
        assertThatThrownBy(() -> action.parse(reader(Map.of("key", "x"))))
                .hasMessageContaining("key: is not available yet");
        assertThatThrownBy(() -> action.parse(reader(Map.of("holder", "x"))))
                .hasMessageContaining("holder: narrows var:");
        assertThatThrownBy(() -> action.parse(reader(Map.of("kind", "gui"))))
                .hasMessageContaining("kind: narrows view:");
        assertThatThrownBy(() -> action.parse(reader(Map.of("var", "server.demo.season", "holder", "x"))))
                .hasMessageContaining("holder: names the player of a player variable");
        assertThatThrownBy(() -> action.parse(reader(Map.of("var", "scratch"))))
                .hasMessageContaining("not the run-local");
    }

    @Test
    void anUndeclaredVariableIsMtr0063AtLoad() {
        assertThatThrownBy(() -> action.parse(reader(Map.of("var", "player.ghost"))))
                .isInstanceOf(ParseRejection.class);
    }

    @Test
    void blankSelectorsAreNoSelectors() {
        // the editor writes an empty field for one the author has not filled in
        assertThat(action.parse(reader(Map.of("view", "", "var", " ")))).isEqualTo(
                new RefreshViewAction.Params(null, null, null, null));
    }
}
