package dev.materione.plugin.gui.input;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.plugin.gui.GuiFixture;
import dev.materione.plugin.gui.open.OpenRequest;
import dev.materione.plugin.gui.session.GuiSession;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The click router (logic.md §6.2) end to end below the Bukkit event: an authored GUI is compiled by the
 * real GUI and rule compilers, a session is opened, and clicks are routed to flows that record what
 * they ran. Covers the topmost-layer rule, filters, fallthrough, layer-less rules, visibility and the
 * context a click's rule sees.
 */
class ClickRouterTest {

    /** Slot 0 is covered by the wells (bottom), the buy button and the sword item (top). */
    private static final String SHOP = """
            shop:
              title: Shop
              rows: 3
              layers:
                - id: wells
                  type: slots
                  region: container
                - id: buy
                  type: button
                  x: 8
                  y: 18
                  width: 16
                  height: 16
                - id: sword
                  type: item
                  slot: 0
                  item: minecraft:diamond_sword
                - id: sell
                  type: button
                  x: 26
                  y: 18
                  width: 16
                  height: 16
              behavior:
                - on: gui.click
                  layer: buy
                  click: left
                  do:
                    - mark: "buy-left slot=${click.slot} layer=${layer.id} type=${click.type}"
                - on: gui.click
                  layer: buy
                  click: [right, shift_left]
                  do:
                    - mark: buy-other
                - on: gui.click
                  layer: sword
                  click: right
                  do:
                    - mark: sword-right
                - on: gui.click
                  layer: sell
                  do:
                    - mark: "sell ${click.type} key=${click.key}"
                - on: gui.click
                  slot: [3, 4]
                  do:
                    - mark: "any-layer slot=${click.slot} layer=[${layer.id}]"
                - on: gui.click_outside
                  click: left
                  do:
                    - mark: outside
            """;

    private GuiFixture f;
    private GuiSession session;

    @BeforeEach
    void open() {
        f = new GuiFixture();
        f.gui(SHOP);
        session = f.opener.open(OpenRequest.of(f.viewer, GuiFixture.id("shop"), Map.of("category", "tools"),
                OpenRequest.Cause.API)).session();
    }

    private ClickRouter.Routed click(int slot, ClickKind kind) {
        return f.router.route(new GuiClick(f.viewer, session, slot, kind, 0));
    }

    @Test
    void aClickReachesTheRuleOfTheLayerUnderIt() {
        // slot 1 is covered by wells and the sell button (x 26..41 holds slot 1's centre at 34)
        ClickRouter.Routed routed = click(1, ClickKind.LEFT);

        assertThat(routed.layer()).isEqualTo("sell");
        assertThat(f.ran).containsExactly("mark:sell left key=0");
    }

    @Test
    void theTopmostLayerWithAMatchingRuleWins() {
        // slot 0: sword (top), buy, wells. A right click matches the sword's rule, which hides the buy rule
        ClickRouter.Routed right = click(0, ClickKind.RIGHT);

        assertThat(right.layer()).isEqualTo("sword");
        assertThat(f.ran).containsExactly("mark:sword-right");
    }

    @Test
    void aLayerWithoutAMatchingRuleLetsTheClickFallThroughToTheNextOne() {
        // the sword only answers right; a left click falls through to the buy button underneath
        ClickRouter.Routed left = click(0, ClickKind.LEFT);

        assertThat(left.layer()).isEqualTo("buy");
        assertThat(f.ran).containsExactly("mark:buy-left slot=0 layer=buy type=left");
    }

    @Test
    void aFilterListMatchesAnyOfItsKinds() {
        click(0, ClickKind.SHIFT_LEFT);

        assertThat(f.ran).containsExactly("mark:buy-other");
    }

    @Test
    void aClickNoRuleAnswersDoesNothing() {
        ClickRouter.Routed routed = click(0, ClickKind.MIDDLE);

        assertThat(routed.handled()).isFalse();
        assertThat(routed).isSameAs(ClickRouter.Routed.NONE);
        assertThat(f.ran).isEmpty();
    }

    @Test
    void aRuleWithNoLayerFilterAnswersAClickNoLayerClaimed() {
        // slot 3 and 4 are covered by the wells only, and the layer-less rule names those slots
        ClickRouter.Routed routed = click(3, ClickKind.LEFT);

        assertThat(routed.layer()).isEmpty();
        assertThat(f.ran).containsExactly("mark:any-layer slot=3 layer=[]");
        // a slot it does not name stays silent
        f.ran.clear();
        assertThat(click(5, ClickKind.LEFT).handled()).isFalse();
    }

    @Test
    void aLayerRuleOutranksTheLayerLessOne() {
        f.gui(SHOP.replace("slot: [3, 4]", "slot: [1]"));
        session = f.opener.open(OpenRequest.of(f.viewer, GuiFixture.id("shop"), Map.of(), OpenRequest.Cause.API))
                .session();

        click(1, ClickKind.LEFT); // slot 1: the sell button claims it, the layer-less slot rule does not run

        assertThat(f.ran).containsExactly("mark:sell left key=0");
    }

    @Test
    void aNumberKeyClickCarriesItsKeyIntoTheContext() {
        f.router.route(new GuiClick(f.viewer, session, 1, ClickKind.NUMBER_KEY, 7));

        assertThat(f.ran).containsExactly("mark:sell number_key key=7");
    }

    @Test
    void aLayerThatIsNotShowingIsSkippedAndTheOneBelowTakesTheClick() {
        f.visibility = (s, layer) -> !layer.equals("sword");

        ClickRouter.Routed routed = click(0, ClickKind.RIGHT);

        assertThat(routed.layer()).isEqualTo("buy");
        assertThat(f.ran).containsExactly("mark:buy-other");
    }

    @Test
    void aClickOnAnEndedSessionIsIgnored() {
        f.sessions.end(session, dev.materione.plugin.gui.session.CloseReason.PLAYER);

        assertThat(click(0, ClickKind.LEFT).handled()).isFalse();
        assertThat(f.ran).isEmpty();
    }

    @Test
    void theContextCarriesTheGuiItsArgumentsAndThePlayer() {
        f.gui(SHOP.replace("buy-left slot", "buy-left ${gui.id} ${gui.args.category} ${player.name} slot"));
        session = f.opener.open(OpenRequest.of(f.viewer, GuiFixture.id("shop"), Map.of("category", "food"),
                OpenRequest.Cause.API)).session();

        click(0, ClickKind.LEFT);

        assertThat(f.ran).containsExactly("mark:buy-left demo:shop food Probe slot=0 layer=buy type=left");
    }

    @Test
    void anOutsideClickRunsTheOutsideRulesOfItsSide() {
        f.router.outside(f.viewer, session, ClickKind.LEFT);
        f.router.outside(f.viewer, session, ClickKind.RIGHT); // the rule is for left only

        assertThat(f.ran).containsExactly("mark:outside");
    }

    @Test
    void aClickIsMarkedAsDispatchingWhileItsRulesRun() {
        // the window host defers a window change made in the middle of a click; the flag is what tells it
        var observed = new java.util.ArrayList<Boolean>();
        f.logic.actions().register(dev.materione.plugin.logic.LogicTestSupport.owner("materione"),
                dev.materione.api.NamespacedId.of("materione", "peek"),
                new PeekAction(() -> observed.add(f.guard.dispatching(f.viewer.id()))));
        f.gui(SHOP.replace("- mark: buy-other", "- peek").replace("click: [right, shift_left]", "click: shift_left"));
        session = f.opener.open(OpenRequest.of(f.viewer, GuiFixture.id("shop"), Map.of(), OpenRequest.Cause.API))
                .session();

        click(0, ClickKind.SHIFT_LEFT);

        assertThat(observed).containsExactly(true);
        assertThat(f.guard.dispatching(f.viewer.id())).isFalse();
    }
}
