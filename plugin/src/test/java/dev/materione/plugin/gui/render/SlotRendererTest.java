package dev.materione.plugin.gui.render;

import static dev.materione.plugin.gui.render.RenderFixtures.barrier;
import static dev.materione.plugin.gui.render.RenderFixtures.conditional;
import static dev.materione.plugin.gui.render.RenderFixtures.gui;
import static dev.materione.plugin.gui.render.RenderFixtures.layer;
import static dev.materione.plugin.gui.render.RenderFixtures.nothing;
import static dev.materione.plugin.gui.render.RenderFixtures.stoneAt;
import static dev.materione.plugin.gui.render.RenderFixtures.template;
import static dev.materione.plugin.gui.render.RenderFixtures.viewer;
import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.logic.ItemResolution;
import dev.materione.content.gui.ItemComponent;
import dev.materione.content.gui.logic.ClickMap;
import dev.materione.plugin.gui.RuntimeGuis;
import dev.materione.plugin.gui.dynamic.CompiledItemLayer;
import dev.materione.plugin.gui.dynamic.ItemBinding;
import dev.materione.plugin.gui.rules.GuiRuleSet;
import java.util.List;
import java.util.Optional;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

/**
 * The window's slot decisions (logic.md §6.3), pure: visibility, the topmost visible layer, barriers,
 * animation, partial and full renders, and the templated overrides. No stacks are built, so no server is needed.
 */
class SlotRendererTest {

    private final RenderFixtures.Reports reports = new RenderFixtures.Reports();
    private final SlotRenderer renderer = new SlotRenderer(reports);

    @Test
    void aStaticSlotShowsItsItemWhateverTheConditions() {
        var rendered = renderer.render(gui(List.of(stoneAt(4)), List.of()), viewer(1), 0, null);

        assertThat(rendered.slots()).containsOnlyKeys(4);
        assertThat(rendered.slots().get(4).source()).isEqualTo(new SlotSource.Vanilla(Material.STONE));
    }

    @Test
    void aHiddenLayerIsRecordedAndDoesNotShow() {
        var live = gui(List.of(conditional("vip", 0, 4, "player.level > 5", barrier("vip"))));

        var rendered = renderer.render(live, viewer(1), 0, null);

        assertThat(rendered.slots()).isEmpty();
        assertThat(rendered.hidden()).containsExactly("vip");
        assertThat(rendered.resolved()).doesNotContainKey("vip");
    }

    @Test
    void theTopmostVisibleLayerOfASlotWinsAndTheOneUnderItIsNotResolved() {
        var live = gui(List.of(
                layer("plain", 0, 4, barrier("low")),
                conditional("vip", 1, 4, "player.level > 5", barrier("high"))));

        var rendered = renderer.render(live, viewer(9), 0, null);

        assertThat(rendered.slots().get(4).source()).isEqualTo(new SlotSource.Barrier("high"));
        assertThat(rendered.resolved()).doesNotContainKey("plain");
    }

    @Test
    void aHiddenTopLayerLetsTheLayerBelowShow() {
        var live = gui(List.of(
                layer("plain", 0, 4, barrier("low")),
                conditional("vip", 1, 4, "player.level > 5", barrier("high"))));

        var rendered = renderer.render(live, viewer(1), 0, null);

        assertThat(rendered.slots().get(4).source()).isEqualTo(new SlotSource.Barrier("low"));
        assertThat(rendered.hidden()).containsExactly("vip");
    }

    @Test
    void aTopmostVisibleLayerThatResolvesToNothingLeavesTheSlotEmpty() {
        var live = gui(List.of(
                layer("plain", 0, 4, barrier("low")),
                conditional("vip", 1, 4, "player.level > 5", nothing())));

        var rendered = renderer.render(live, viewer(9), 0, null);

        assertThat(rendered.slots()).isEmpty();
        assertThat(rendered.resolved()).containsEntry("vip", Optional.empty());
    }

    @Test
    void aSourceThatThrowsShowsABarrierWithItsReasonAndIsReported() {
        ItemBinding throwing = context -> {
            throw new IllegalStateException("the player has no head");
        };

        var rendered = renderer.render(gui(List.of(layer("head", 0, 4, throwing))), viewer(1), 0, null);

        assertThat(rendered.slots().get(4).source()).isEqualTo(new SlotSource.Barrier("the player has no head"));
        assertThat(reports.codes).containsExactly("MTR-0072");
    }

    @Test
    void anUnresolvedSourceIsABarrierAndIsReportedOnce() {
        var rendered = renderer.render(gui(List.of(layer("head", 0, 4, barrier("no such player")))), viewer(1), 0,
                null);

        assertThat(rendered.slots().get(4).source()).isEqualTo(new SlotSource.Barrier("no such player"));
        assertThat(reports.codes).containsExactly("MTR-0072");
        assertThat(reports.messages.get(0)).startsWith("head: no such player");
    }

    @Test
    void framesFollowTheWindowClockEveryNTicks() {
        var animated = new CompiledItemLayer("anim", 0, 4, null, null,
                List.of(barrier("a"), barrier("b")), 10, null, List.of(), ItemComponent.LoreMode.REPLACE,
                template("1"), null, true);
        var live = gui(List.of(animated));

        assertThat(shownReason(renderer.render(live, viewer(1), 0, null))).isEqualTo("a");
        assertThat(shownReason(renderer.render(live, viewer(1), 9, null))).isEqualTo("a");
        assertThat(shownReason(renderer.render(live, viewer(1), 10, null))).isEqualTo("b");
        assertThat(shownReason(renderer.render(live, viewer(1), 20, null))).isEqualTo("a");
    }

    private static String shownReason(dev.materione.plugin.gui.render.Rendered rendered) {
        return ((SlotSource.Barrier) rendered.slots().get(4).source()).reason();
    }

    @Test
    void aPartialRenderKeepsTheStaticItemsAndReReadsOnlyTheDynamicOnes() {
        int[] staticReads = {0};
        int[] dynamicReads = {0};
        ItemBinding counted = context -> {
            staticReads[0]++;
            return ItemResolution.unresolved("static");
        };
        ItemBinding countedDynamic = context -> {
            dynamicReads[0]++;
            return ItemResolution.unresolved("dynamic");
        };
        var live = gui(List.of(
                layer("fixed", 0, 1, counted),
                conditional("moving", 1, 2, "player.level > 0", countedDynamic)));

        var first = renderer.render(live, viewer(3), 0, null);
        assertThat(staticReads[0]).isEqualTo(1);
        assertThat(dynamicReads[0]).isEqualTo(1);

        var partial = renderer.render(live, viewer(3), 20, first);
        assertThat(staticReads[0]).as("the static layer keeps what it resolved").isEqualTo(1);
        assertThat(dynamicReads[0]).as("the dynamic layer is read again").isEqualTo(2);
        assertThat(partial.slots()).isEqualTo(first.slots());
    }

    @Test
    void theTemplatedOverridesRenderForTheViewer() {
        var layer = new CompiledItemLayer("named", 0, 4, null, barrier("unused"), null, 0,
                template("Hi ${player.name}"),
                List.of(template("level ${player.level}"), template("plain")), ItemComponent.LoreMode.APPEND,
                template("${player.level * 10}"), template("${player.level > 5}"), true);

        var overrides = renderer.overridesOf(new SlotRenderer.Pass(RenderFixtures.GUI, viewer(7), 0), layer);

        assertThat(overrides.name()).isEqualTo("Hi Probe");
        assertThat(overrides.lore()).containsExactly("level 7", "plain");
        assertThat(overrides.amount()).as("clamped to a chest stack").isEqualTo(ItemComponent.MAX_AMOUNT);
        assertThat(overrides.glint()).isTrue();
    }

    @Test
    void anAmountThatIsNotANumberShowsOneAndIsReported() {
        var layer = new CompiledItemLayer("counted", 0, 4, null, barrier("unused"), null, 0, null, List.of(),
                ItemComponent.LoreMode.REPLACE, template("oops"), null, true);

        var overrides = renderer.overridesOf(new SlotRenderer.Pass(RenderFixtures.GUI, viewer(1), 0), layer);

        assertThat(overrides.amount()).isEqualTo(1);
        assertThat(reports.codes).containsExactly("MTR-0073");
    }

    @Test
    void aGlintThatIsNotBooleanKeepsTheItemsOwnGlint() {
        var layer = new CompiledItemLayer("glinted", 0, 4, null, barrier("unused"), null, 0, null, List.of(),
                ItemComponent.LoreMode.REPLACE, template("1"), template("maybe"), true);

        assertThat(renderer.overridesOf(new SlotRenderer.Pass(RenderFixtures.GUI, viewer(1), 0), layer).glint())
                .isNull();
    }

    @Test
    void theClockIsTheGreatestCommonDivisorOfTheRefreshAndEveryAnimation() {
        var animated = new CompiledItemLayer("anim", 0, 4, null, null, List.of(barrier("a")), 10, null, List.of(),
                ItemComponent.LoreMode.REPLACE, template("1"), null, true);
        var clocked = new RuntimeGuis.Live(RenderFixtures.GUI, 3, "Shop", "Shop", List.of(), ClickMap.EMPTY,
                GuiRuleSet.EMPTY, List.of(), 20, List.of(animated));
        var off = new RuntimeGuis.Live(RenderFixtures.GUI, 3, "Shop", "Shop", List.of(), ClickMap.EMPTY,
                GuiRuleSet.EMPTY, List.of(), 0, List.of());

        assertThat(clocked.clockPeriod()).isEqualTo(10);
        assertThat(off.clockPeriod()).isZero();
    }
}
