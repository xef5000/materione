package dev.materione.plugin.logic.condition;

import static dev.materione.plugin.logic.condition.ConditionFixtures.params;
import static dev.materione.plugin.logic.condition.ConditionFixtures.reader;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.Facets;
import dev.materione.behavior.logic.MapLogicContext;
import dev.materione.plugin.logic.action.PlayerFake;
import dev.materione.plugin.logic.support.OpenGuis;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The place and GUI conditions (logic.md §19): {@code is_near}, {@code in_world} and {@code in_gui}. The fake
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

    // --- in_gui ----------------------------------------------------------------

    private static final NamespacedId SHOP = NamespacedId.of("shop", "menu");

    /** A port that reports {@code SHOP} open for every player, or nothing. */
    private static OpenGuis showing(Optional<NamespacedId> open) {
        return player -> open;
    }

    @Test
    void inGuiWithoutAnIdHoldsWhenAnyGuiIsOpen() {
        var fake = new PlayerFake();
        var parsed = new InGuiCondition(showing(Optional.of(SHOP))).parse(reader(params()));
        assertThat(parsed.id()).isEmpty();
        assertThat(new InGuiCondition(showing(Optional.of(SHOP))).test(parsed, fake.context())).isTrue();
        assertThat(new InGuiCondition(showing(Optional.empty())).test(parsed, fake.context())).isFalse();
    }

    @Test
    void inGuiWithAnIdHoldsOnlyForThatGui() {
        var fake = new PlayerFake();
        var mine = new InGuiCondition(showing(Optional.of(SHOP))).parse(reader(params("id", "shop:menu")));
        var other = new InGuiCondition(showing(Optional.of(SHOP))).parse(reader(params("id", "shop:settings")));
        assertThat(new InGuiCondition(showing(Optional.of(SHOP))).test(mine, fake.context())).isTrue();
        assertThat(new InGuiCondition(showing(Optional.of(SHOP))).test(other, fake.context())).isFalse();
    }

    @Test
    void inGuiIsFalseWhenNothingIsOpenOrNoPortIsWired() {
        var fake = new PlayerFake();
        var parsed = new InGuiCondition(OpenGuis.none()).parse(reader(params("id", "shop:menu")));
        assertThat(new InGuiCondition(OpenGuis.none()).test(parsed, fake.context())).isFalse();
        var any = new InGuiCondition(OpenGuis.none()).parse(reader(params()));
        assertThat(new InGuiCondition(OpenGuis.none()).test(any, fake.context())).isFalse();
    }

    @Test
    void inGuiRejectsAnIdThatIsNotAnId() {
        assertThatThrownBy(() -> new InGuiCondition(OpenGuis.none()).parse(reader(params("id", "not an id!"))))
                .hasMessage("id must name a GUI, such as shop:menu");
    }

    @Test
    void theInGuiContextIsReadOnlyThroughTheFacetAndNeedsAPlayer() {
        var parsed = new InGuiCondition(showing(Optional.of(SHOP))).parse(reader(params()));
        assertThat(new InGuiCondition(showing(Optional.of(SHOP))).test(parsed, PlayerFake.noPlayer())).isFalse();
    }
}
