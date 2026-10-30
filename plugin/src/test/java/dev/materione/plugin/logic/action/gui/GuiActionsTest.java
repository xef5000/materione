package dev.materione.plugin.logic.action.gui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.Facets;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.Threading;
import dev.materione.behavior.logic.EngineExpressions;
import dev.materione.behavior.logic.MapLogicContext;
import dev.materione.plugin.gui.GuiBridge;
import dev.materione.plugin.gui.GuiFixture;
import dev.materione.plugin.gui.event.GuiContexts;
import dev.materione.plugin.gui.input.ClickKind;
import dev.materione.plugin.gui.input.GuiClick;
import dev.materione.plugin.gui.open.OpenRequest;
import dev.materione.plugin.gui.session.CloseReason;
import dev.materione.plugin.gui.session.GuiSession;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The four GUI actions (logic.md §6): each parses strictly, describes itself, and runs against the real
 * GUI runtime over a fake window host. The click-driven tests prove the whole path an author writes;
 * the execute tests prove the refusals a run can hit (a session that ended, no player).
 */
class GuiActionsTest {

    private static final String MENU = """
            menu:
              title: Menu
              rows: 3
              layers:
                - id: go
                  type: button
                  x: 8
                  y: 18
                  width: 16
                  height: 16
                - id: back
                  type: button
                  x: 26
                  y: 18
                  width: 16
                  height: 16
                - id: refresh
                  type: button
                  x: 44
                  y: 18
                  width: 16
                  height: 16
                - id: close
                  type: button
                  x: 62
                  y: 18
                  width: 16
                  height: 16
              behavior:
                - on: gui.click
                  layer: go
                  do:
                    - gui.open: {gui: detail, args: {item: "sword", who: "${player.name}"}}
                - on: gui.click
                  layer: back
                  do:
                    - gui.back
                - on: gui.click
                  layer: refresh
                  do:
                    - gui.refresh
                    - mark: refreshed
                - on: gui.click
                  layer: close
                  do:
                    - mark: closing
                    - gui.close
                    - mark: after-close
                - on: gui.close
                  do:
                    - mark: "menu-closed ${gui.id}"
            """;

    private static final String DETAIL = """
            detail:
              title: Detail
              rows: 1
              layers:
                - id: up
                  type: button
                  x: 8
                  y: 18
                  width: 16
                  height: 16
              behavior:
                - on: gui.open
                  do:
                    - mark: "detail ${gui.args.item} for ${gui.args.who}"
                - on: gui.click
                  layer: up
                  do:
                    - gui.back
                    - mark: "back-done"
            """;

    private GuiFixture f;
    private GuiSession menu;

    @BeforeEach
    void setUp() {
        f = new GuiFixture();
        f.gui(MENU);
        f.gui(DETAIL);
        menu = f.opener.open(OpenRequest.of(f.viewer, GuiFixture.id("menu"), Map.of("page", "2"),
                OpenRequest.Cause.API)).session();
        menu.vars().put("tab", "weapons");
        f.ran.clear();
    }

    private void click(GuiSession session, int slot) {
        f.router.route(new GuiClick(f.viewer, session, slot, ClickKind.LEFT, 0));
    }

    /** A context for a run that belongs to {@code session} (or to none). */
    private MapLogicContext context(GuiSession session) {
        return GuiContexts.build(f.viewer, session, Map.of());
    }

    // --- the whole path an author writes ----------------------------------------------

    @Test
    void openingFromAClickOpensTheOtherGuiWithRenderedArgumentsAndRemembersThisOne() {
        click(menu, 0); // the go button

        GuiSession detail = f.sessions.current(f.viewer.id()).orElseThrow();
        assertThat(detail.gui()).isEqualTo(GuiFixture.id("detail"));
        assertThat(detail.args()).containsEntry("item", "sword").containsEntry("who", "Probe");
        assertThat(f.ran).contains("mark:detail sword for Probe", "mark:menu-closed demo:menu");
        assertThat(menu.endedBecause()).isEqualTo(CloseReason.REPLACED);
        assertThat(f.windows.log).containsExactly("open menu", "open detail");
    }

    @Test
    void goingBackFromTheOpenedGuiBringsBackTheArgumentsAndSessionVariables() {
        click(menu, 0);
        GuiSession detail = f.sessions.current(f.viewer.id()).orElseThrow();
        f.ran.clear();

        click(detail, 0); // detail's up button runs gui.back, then a mark

        GuiSession restored = f.sessions.current(f.viewer.id()).orElseThrow();
        assertThat(restored.gui()).isEqualTo(GuiFixture.id("menu"));
        assertThat(restored).isNotSameAs(menu);
        assertThat(restored.args()).containsEntry("page", "2");
        assertThat(restored.vars()).containsEntry("tab", "weapons");
        // the flow that went back keeps running: its session ended, but runs belong to the player
        assertThat(f.ran).contains("mark:back-done");
        assertThat(detail.endedBecause()).isEqualTo(CloseReason.REPLACED);
    }

    @Test
    void goingBackWithNothingBehindFailsAndKeepsTheWindow() {
        click(menu, 1); // the back button, with an empty stack

        assertThat(f.sessions.current(f.viewer.id())).containsSame(menu);
        assertThat(f.diagnostics).extracting(d -> d.message()).anyMatch(m -> m.contains("nothing to go back to"));
    }

    @Test
    void refreshRewritesTheWindowInPlaceAndKeepsTheSession() {
        click(menu, 2);

        assertThat(f.windows.log).containsExactly("open menu", "rewrite menu");
        assertThat(f.sessions.current(f.viewer.id())).containsSame(menu);
        assertThat(menu.vars()).containsEntry("tab", "weapons");
        assertThat(f.ran).containsExactly("mark:refreshed");
    }

    @Test
    void closeEndsTheSessionFiresCloseAndTheFlowCarriesOn() {
        click(menu, 3);

        assertThat(menu.endedBecause()).isEqualTo(CloseReason.ACTION);
        assertThat(f.sessions.current(f.viewer.id())).isEmpty();
        assertThat(f.windows.log).containsExactly("open menu", "close");
        // runs belong to the player, not the window: the step after gui.close still ran
        assertThat(f.ran).containsExactly("mark:closing", "mark:menu-closed demo:menu", "mark:after-close");
    }

    @Test
    void closingDropsTheBackStack() {
        click(menu, 0);
        assertThat(f.sessions.history(f.viewer.id()).size()).isEqualTo(1);
        GuiSession detail = f.sessions.current(f.viewer.id()).orElseThrow();

        f.lifecycle.closeByAction(f.viewer, detail);

        assertThat(f.sessions.history(f.viewer.id()).size()).isZero();
    }

    // --- refusals ------------------------------------------------------------------------

    @Nested
    class Refusals {

        @Test
        void aRunWhoseSessionEndedHasNoOpenGui() {
            MapLogicContext ctx = context(menu);
            f.sessions.end(menu, CloseReason.PLAYER);
            ctx.with(Facets.PLAYER, f.player);

            for (ActionType<?> action : List.of(new CloseGuiAction(f.bridge), new BackGuiAction(f.bridge),
                    new RefreshGuiAction(f.bridge))) {
                assertThat(run(action, ctx)).as(action.id()).isEqualTo(ActionOutcome.failed("no open gui"));
            }
        }

        @Test
        void aRunWithNoSessionHasNoOpenGuiEither() {
            MapLogicContext ctx = new MapLogicContext(Map.of()).with(Facets.PLAYER, f.player);

            assertThat(run(new CloseGuiAction(f.bridge), ctx)).isEqualTo(ActionOutcome.failed("no open gui"));
        }

        @Test
        void aRunWithoutAPlayerCannotUseAnyGuiAction() {
            MapLogicContext ctx = new MapLogicContext(Map.of());

            for (ActionType<?> action : List.of(new OpenGuiAction(f.bridge), new CloseGuiAction(f.bridge),
                    new BackGuiAction(f.bridge), new RefreshGuiAction(f.bridge))) {
                ActionOutcome outcome = run(action, ctx);
                assertThat(outcome).as(action.id()).isInstanceOf(ActionOutcome.Failed.class);
                assertThat(((ActionOutcome.Failed) outcome).reason()).isEqualTo("event has no player");
            }
        }

        @Test
        void anUnboundBridgeFailsSoftInsteadOfThrowing() {
            var ctx = context(menu);
            var unbound = new CloseGuiAction(new GuiBridge());

            ActionOutcome outcome = unbound.execute(unbound.parse(reader(Map.of())), ctx);

            assertThat(outcome).isEqualTo(ActionOutcome.failed("the GUI runtime is not available"));
        }

        @Test
        void aSessionThatIsNoLongerThePlayersCurrentOneIsNoOpenGui() {
            // the player opened something else behind this run's back
            f.sessions.begin(new GuiSession(f.viewer.id(), "Probe", GuiFixture.id("detail"), Map.of()));

            assertThat(run(new CloseGuiAction(f.bridge), context(menu)))
                    .isEqualTo(ActionOutcome.failed("no open gui"));
        }

        @Test
        void openingAnUnknownGuiFailsWithTheName() {
            var action = new OpenGuiAction(f.bridge);

            ActionOutcome outcome = action.execute(action.parse(reader(Map.of("gui", "ghost"))), context(menu));

            assertThat(outcome).isEqualTo(ActionOutcome.failed("no live GUI matches 'ghost'"));
        }

        @Test
        void openingFromARunWithNoSessionStillWorks() {
            f.sessions.end(menu, CloseReason.PLAYER); // e.g. an item rule, or a flow that outlived its window
            var action = new OpenGuiAction(f.bridge);
            var ctx = new MapLogicContext(Map.of("player", Map.of("name", "Probe"))).with(Facets.PLAYER, f.player);

            ActionOutcome outcome = action.execute(action.parse(reader(Map.of("gui", "demo:detail",
                    "args", Map.of("item", "bow", "who", "${player.name}")))), ctx);

            assertThat(outcome).isEqualTo(ActionOutcome.OK);
            assertThat(f.sessions.current(f.viewer.id()).orElseThrow().args()).containsEntry("who", "Probe");
        }

        @Test
        void aBareNameMeansTheGuiOfThatNameInThisGuisPack() {
            var action = new OpenGuiAction(f.bridge);

            assertThat(action.execute(action.parse(reader(Map.of("gui", "detail"))), context(menu)))
                    .isEqualTo(ActionOutcome.OK);
            assertThat(f.sessions.current(f.viewer.id()).orElseThrow().gui()).isEqualTo(GuiFixture.id("detail"));
        }

        @Test
        void openingRefusedByTheGateIsAFailureWithItsReason() {
            f.opener.gate((viewer, gui, args) -> dev.materione.plugin.gui.open.OpenGate.Admission.deny("members only"));
            var action = new OpenGuiAction(f.bridge);

            assertThat(action.execute(action.parse(reader(Map.of("gui", "detail"))), context(menu)))
                    .isEqualTo(ActionOutcome.failed("members only"));
            assertThat(f.sessions.current(f.viewer.id())).as("the window stays").containsSame(menu);
        }
    }

    // --- description and parse ------------------------------------------------------------

    @Nested
    class Description {

        @Test
        void eachActionDescribesItselfAsAGuiActionOnThePlayersThread() {
            List<ActionType<?>> actions = List.of(new OpenGuiAction(f.bridge), new CloseGuiAction(f.bridge),
                    new BackGuiAction(f.bridge), new RefreshGuiAction(f.bridge));

            assertThat(actions).extracting(ActionType::id)
                    .containsExactly("gui.open", "gui.close", "gui.back", "gui.refresh");
            assertThat(actions).allSatisfy(action -> {
                assertThat(action.describe().category()).isEqualTo(LogicCategory.GUI);
                assertThat(action.threading()).isEqualTo(Threading.PLAYER_REGION);
                assertThat(action.describe().self().displayName()).isNotBlank();
            });
            assertThat(new OpenGuiAction(f.bridge).describe().shorthand()).isEqualTo("gui");
            assertThat(new CloseGuiAction(f.bridge).describe().shorthand()).isNull();
        }

        @Test
        void openAcceptsAGuiAndTemplatedArguments() {
            var action = new OpenGuiAction(f.bridge);

            OpenGuiAction.Params params = action.parse(reader(Map.of("gui", "shop:main",
                    "args", Map.of("category", "tools", "who", "${player.name}"))));

            assertThat(params.gui()).isEqualTo("shop:main");
            assertThat(params.args()).containsOnlyKeys("category", "who");
        }

        @Test
        void openRejectsAMissingGuiAndBadArguments() {
            var action = new OpenGuiAction(f.bridge);

            assertThatThrownBy(() -> action.parse(reader(Map.of())))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> action.parse(reader(Map.of("gui", "x", "args", "not a mapping"))))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> action.parse(reader(Map.of("gui", "x", "args", Map.of("a", List.of(1))))))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void theParameterlessActionsAcceptNoParametersAndTakeNoBareValue() {
            // their bare-name form (`- gui.close`) is an empty mapping
            assertThat(new CloseGuiAction(f.bridge).parse(reader(Map.of()))).isNotNull();
            assertThat(new BackGuiAction(f.bridge).parse(reader(Map.of()))).isNotNull();
            assertThat(new RefreshGuiAction(f.bridge).parse(reader(Map.of()))).isNotNull();
        }
    }

    private static ParamReader reader(Map<String, Object> params) {
        return ParamReader.of(params, EngineExpressions.INSTANCE);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ActionOutcome run(ActionType action, MapLogicContext ctx) {
        // gui.open needs its gui; the other actions take no parameters and ignore the key
        return action.execute(action.parse(reader(Map.of("gui", "detail"))), ctx);
    }
}
