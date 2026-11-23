package dev.materione.plugin.logic.condition;

import static dev.materione.plugin.logic.condition.ConditionFixtures.params;
import static dev.materione.plugin.logic.condition.ConditionFixtures.reader;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.Facets;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.variable.CompileEnvironment;
import dev.materione.behavior.logic.EngineExpressions;
import dev.materione.behavior.logic.MapLogicContext;
import dev.materione.plugin.logic.action.PlayerFake;
import dev.materione.plugin.logic.support.OpenViews;
import dev.materione.plugin.view.ViewRef;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The place and GUI conditions (logic.md §22): {@code is_near}, {@code in_world} and {@code in_view}. The fake
 * player stands at (1, 2, 3) in a world named {@code world}, with the facing fixed by {@link PlayerFake}.
 */
class PlaceAndGuiConditionsTest {

    // --- is_near ---------------------------------------------------------------

    private final IsNearCondition near = new IsNearCondition();

    @Test
    void isNearMeasuresStraightLineDistanceWithinTheRadius() {
        var fake = new PlayerFake();
        var atTheSpot = near.parse(reader(params("x", 1, "y", 2, "z", 3, "distance", 0)));
        assertThat(near.test(atTheSpot, fake.context())).isTrue();

        // a 3-4-5 triangle on the ground: the point (4, 2, 7) is 5 blocks away
        var fiveAway = near.parse(reader(params("x", 4, "y", 2, "z", 7, "distance", 5)));
        var fourAway = near.parse(reader(params("x", 4, "y", 2, "z", 7, "distance", 4.9)));
        assertThat(near.test(fiveAway, fake.context())).isTrue();
        assertThat(near.test(fourAway, fake.context())).isFalse();
    }

    @Test
    void isNearRequiresTheNamedWorldWhenOneIsGiven() {
        var fake = new PlayerFake();
        var here = near.parse(reader(params("world", "world", "x", 1, "y", 2, "z", 3, "distance", 1)));
        var there = near.parse(reader(params("world", "nether", "x", 1, "y", 2, "z", 3, "distance", 1)));
        assertThat(near.test(here, fake.context())).isTrue();
        assertThat(near.test(there, fake.context())).isFalse();
    }

    @Test
    void isNearReadsTemplatedNumbersAtTestTime() {
        var fake = new PlayerFake();
        var parsed = near.parse(reader(params("x", "${vars.sx}", "y", 2, "z", 3, "distance", 2)));
        var context = new MapLogicContext(Map.of("vars", Map.of("sx", 2)))
                .with(Facets.PLAYER, fake.player);
        assertThat(near.test(parsed, context)).isTrue();
    }

    @Test
    void isNearIsFalseWhenATemplateDoesNotRenderToANumber() {
        var fake = new PlayerFake();
        var parsed = near.parse(reader(params("x", "${vars.sx}", "y", 2, "z", 3, "distance", 2)));
        var context = new MapLogicContext(Map.of("vars", Map.of("sx", "far")))
                .with(Facets.PLAYER, fake.player);
        assertThat(near.test(parsed, context)).isFalse();
    }

    @Test
    void isNearRejectsMissingCoordinatesANegativeDistanceAndAStringThatIsNotANumber() {
        assertThatThrownBy(() -> near.parse(reader(params("x", 1, "y", 2, "z", 3))))
                .hasMessage("distance is required");
        assertThatThrownBy(() -> near.parse(reader(params("x", 1, "y", 2, "z", 3, "distance", -1))))
                .hasMessageContaining("distance must be a number in 0.0..6.0E7");
        assertThatThrownBy(() -> near.parse(reader(params("x", "north", "y", 2, "z", 3, "distance", 1))))
                .hasMessageContaining("x must be a number or a template");
    }

    @Test
    void isNearWithoutAPlayerIsFalse() {
        var parsed = near.parse(reader(params("x", 1, "y", 2, "z", 3, "distance", 1)));
        assertThat(near.test(parsed, PlayerFake.noPlayer())).isFalse();
    }

    // --- in_world --------------------------------------------------------------

    private final InWorldCondition inWorld = new InWorldCondition();

    @Test
    void inWorldTakesOneNameOrAListAndMatchesExactly() {
        var fake = new PlayerFake();
        assertThat(inWorld.test(inWorld.parse(reader(params("worlds", "world"))), fake.context())).isTrue();
        assertThat(inWorld.test(inWorld.parse(reader(params("worlds", "World"))), fake.context())).isFalse();
        assertThat(inWorld.test(inWorld.parse(reader(params("worlds", List.of("nether", "world")))), fake.context()))
                .isTrue();
        assertThat(inWorld.test(inWorld.parse(reader(params("worlds", List.of("nether", "the_end")))), fake.context()))
                .isFalse();
    }

    @Test
    void inWorldRejectsAnEmptyOrMissingList() {
        assertThatThrownBy(() -> inWorld.parse(reader(params())))
                .hasMessage("worlds is required");
        assertThatThrownBy(() -> inWorld.parse(reader(params("worlds", List.of()))))
                .hasMessage("worlds needs at least one world name");
    }

    @Test
    void inWorldWithoutAPlayerIsFalse() {
        var parsed = inWorld.parse(reader(params("worlds", "world")));
        assertThat(inWorld.test(parsed, PlayerFake.noPlayer())).isFalse();
    }

    // --- in_view ---------------------------------------------------------------

    private static final NamespacedId SHOP = NamespacedId.of("shop", "menu");
    private static final NamespacedId ASK = NamespacedId.of("shop", "ask");

    private static InViewCondition condition(ViewRef... open) {
        return new InViewCondition(player -> List.of(open));
    }

    @Test
    void inViewWithoutAnIdHoldsWhenAnyViewIsOpen() {
        var fake = new PlayerFake();
        var parsed = condition().parse(reader(params()));
        assertThat(parsed.view()).isNull();
        assertThat(parsed.kind()).isNull();
        assertThat(condition(new ViewRef("gui", SHOP)).test(parsed, fake.context())).isTrue();
        assertThat(condition(new ViewRef("dialog", SHOP)).test(parsed, fake.context())).isTrue();
        assertThat(condition().test(parsed, fake.context())).isFalse();
    }

    @Test
    void inViewWithAnIdHoldsOnlyForThatView() {
        var fake = new PlayerFake();
        var gui = condition(new ViewRef("gui", SHOP));
        var mine = gui.parse(reader(params("view", "shop:menu")));
        var other = gui.parse(reader(params("view", "shop:settings")));
        assertThat(gui.test(mine, fake.context())).isTrue();
        assertThat(gui.test(other, fake.context())).isFalse();
    }

    @Test
    void inViewWithAKindHoldsOnlyForAViewOfThatKind() {
        var fake = new PlayerFake();
        var dialogOnly = condition().parse(reader(params("kind", "dialog")));
        var shopDialog = condition().parse(reader(params("view", "shop:menu", "kind", "dialog")));

        assertThat(condition(new ViewRef("gui", SHOP)).test(dialogOnly, fake.context())).isFalse();
        assertThat(condition(new ViewRef("gui", SHOP), new ViewRef("dialog", ASK)).test(dialogOnly, fake.context()))
                .as("a dialog over the GUI").isTrue();
        assertThat(condition(new ViewRef("gui", SHOP), new ViewRef("dialog", ASK)).test(shopDialog, fake.context()))
                .as("the id is the GUI's, the kind is the dialog's").isFalse();
        assertThat(condition(new ViewRef("dialog", SHOP)).test(shopDialog, fake.context())).isTrue();
    }

    @Test
    void aBareNameMeansThatNameInTheConditionsOwnPack() {
        var env = CompileEnvironment.of("shop", id -> Optional.empty());
        var reader = ParamReader.of(params("view", "menu"), EngineExpressions.INSTANCE, env);

        assertThat(condition().parse(reader).view()).isEqualTo(SHOP);
        assertThat(condition().parse(reader(params("view", "menu"))).view())
                .as("a condition with no pack of its own").isEqualTo(NamespacedId.of("materione", "menu"));
    }

    @Test
    void inViewIsFalseWhenNothingIsOpenOrNoPortIsWired() {
        var fake = new PlayerFake();
        var none = new InViewCondition(OpenViews.none());
        assertThat(none.test(none.parse(reader(params("view", "shop:menu"))), fake.context())).isFalse();
        assertThat(none.test(none.parse(reader(params())), fake.context())).isFalse();
    }

    @Test
    void inViewRejectsAnIdThatIsNotAnId() {
        assertThatThrownBy(() -> new InViewCondition(OpenViews.none()).parse(reader(params("view", "not an id!"))))
                .hasMessage("view must name a view, such as shop:menu");
    }

    @Test
    void theInViewContextIsReadOnlyThroughTheFacetAndNeedsAPlayer() {
        var open = condition(new ViewRef("gui", SHOP));
        assertThat(open.test(open.parse(reader(params())), PlayerFake.noPlayer())).isFalse();
    }
}
