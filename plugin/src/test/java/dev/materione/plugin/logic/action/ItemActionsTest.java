package dev.materione.plugin.logic.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.plugin.logic.support.InventoryPlan;
import dev.materione.plugin.logic.support.InventoryPlan.Take;
import dev.materione.plugin.logic.support.ItemCatalog;
import dev.materione.plugin.logic.support.ItemRef;
import dev.materione.plugin.logic.support.ItemView;
import dev.materione.plugin.logic.support.Quantity;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The item actions and their shared matcher, with no server: the take plan is pure and checked
 * exhaustively here, the actions are checked on their parse and on the paths that fail before any
 * stack is built. Building and delivering real stacks needs a server and is proven by the QA suite.
 */
class ItemActionsTest {

    private static final NamespacedId RUBY = NamespacedId.parse("materione:ruby");

    private static ItemView vanilla(String material, int amount) {
        return new ItemView(Optional.empty(), material, Optional.empty(), amount);
    }

    private static ItemView ruby(int amount, String name) {
        return new ItemView(Optional.of(RUBY), "PAPER", Optional.ofNullable(name), amount);
    }

    private static Predicate<ItemView> diamonds() {
        return new ItemRef.Vanilla("DIAMOND")::matches;
    }

    @Nested
    class Plan {
        private final List<ItemView> slots = Arrays.asList(
                vanilla("DIAMOND", 3), null, ruby(5, "Ruby Gem"), vanilla("DIAMOND", 4));

        @Test
        void takesFromTheFirstSlotsThatHoldTheItemAndCountsEverything() {
            assertThat(InventoryPlan.count(slots, diamonds())).isEqualTo(7);
            assertThat(InventoryPlan.plan(slots, diamonds(), 5))
                    .contains(List.of(new Take(0, 3), new Take(3, 2)));
            assertThat(InventoryPlan.plan(slots, diamonds(), 7))
                    .contains(List.of(new Take(0, 3), new Take(3, 4)));
        }

        @Test
        void aShortInventoryHasNoPlanAtAllSoNothingIsRemoved() {
            assertThat(InventoryPlan.plan(slots, diamonds(), 8)).isEmpty();
            assertThat(InventoryPlan.plan(slots, diamonds(), 0)).isEmpty();
        }

        @Test
        void aNameFilterNarrowsTheMatchAndAMaterioneIdNeverMatchesAVanillaRef() {
            Predicate<ItemView> rubies = InventoryPlan.wanted(new ItemRef.Materione(RUBY), Optional.of("gem"));
            assertThat(InventoryPlan.count(slots, rubies)).isEqualTo(5);
            assertThat(InventoryPlan.count(slots, InventoryPlan.wanted(new ItemRef.Materione(RUBY),
                    Optional.of("sapphire")))).isZero();

            // a stack that carries a Materione identity is never "minecraft:paper" for take purposes
            assertThat(new ItemRef.Vanilla("PAPER").matches(ruby(1, null))).isFalse();
            assertThat(new ItemRef.Vanilla("PAPER").matches(vanilla("PAPER", 1))).isTrue();
        }
    }

    @Nested
    class Refs {
        @Test
        void aVanillaReferenceResolvesWithoutAnyContent() {
            assertThat(ItemRef.resolve("minecraft:diamond", null)).isEqualTo(new ItemRef.Vanilla("DIAMOND"));
            assertThatThrownBy(() -> ItemRef.resolve("minecraft:Not-A-Thing", null))
                    .hasMessage("'minecraft:Not-A-Thing' is not a vanilla item id");
        }

        @Test
        void aLiteralVanillaNameIsCheckedWhenTheStepLoads() {
            ItemRef.checkLiteral("minecraft:diamond");
            ItemRef.checkLiteral("ruby");
            ItemRef.checkLiteral("${vars.item}");
            assertThatThrownBy(() -> ItemRef.checkLiteral("minecraft:not a thing"))
                    .hasMessage("'minecraft:not a thing' is not a vanilla item id");
        }

        @Test
        void aMaterioneReferenceNeedsLoadedContent() {
            assertThatThrownBy(() -> ItemRef.resolve("ruby", null)).hasMessage("no content is loaded yet");
        }
    }

    @Nested
    class Quantities {
        @Test
        void acceptsLiteralsAndTemplatesAndRejectsStringsOutOfRangeAndNonNumbers() {
            assertThat(Quantity.required(reader("amount", 5), "amount", 1, 10).literal()).isEqualTo(5d);
            assertThat(Quantity.required(reader("amount", "${vars.n}"), "amount", 1, 10).template()).isNotNull();
            assertThat(Quantity.optional(reader(), "amount", 1, 10)).isEmpty();

            assertThatThrownBy(() -> Quantity.required(reader("amount", "5"), "amount", 1, 10))
                    .hasMessage("amount must be a number or a template that renders to one");
            assertThatThrownBy(() -> Quantity.required(reader("amount", 11), "amount", 1, 10))
                    .hasMessage("amount must be a number in 1.0..10.0");
            assertThatThrownBy(() -> Quantity.required(reader(), "amount", 1, 10)).hasMessage("amount is required");
        }

        @Test
        void aCountRejectsFractionsAtLoad() {
            assertThat(Quantity.requiredCount(reader("amount", 3), "amount", 1, 10).whole()).isTrue();
            assertThatThrownBy(() -> Quantity.requiredCount(reader("amount", 1.5), "amount", 1, 10))
                    .hasMessage("amount must be a whole number in 1..10");
        }

        private static dev.materione.api.logic.ParamReader reader(Object... keyValues) {
            return PlayerActionsTest.reader(PlayerActionsTest.params(keyValues));
        }
    }

    @Nested
    class Actions {
        @Test
        void takeRejectsBadAmountsAndFailsWithoutAPlayer() {
            var take = new TakeItemAction(ItemCatalog.none());
            assertThatThrownBy(() -> take.parse(PlayerActionsTest.reader(PlayerActionsTest.params("item", "x"))))
                    .hasMessage("amount is required");
            var parsed = take.parse(PlayerActionsTest.reader(PlayerActionsTest.params("item", "x", "amount", 1)));
            assertThat(take.execute(parsed, PlayerFake.noPlayer()))
                    .isEqualTo(ActionOutcome.failed("event has no player"));
        }

        @Test
        void takeFailsAndTakesNothingWhenThePlayerHoldsTooFew() {
            var fake = new PlayerFake();
            var take = new TakeItemAction(ItemCatalog.none());
            var parsed = take.parse(PlayerActionsTest.reader(
                    PlayerActionsTest.params("item", "minecraft:diamond", "amount", 2)));

            assertThat(take.execute(parsed, fake.context()))
                    .isEqualTo(ActionOutcome.failed("player holds 0 of 'minecraft:diamond', needs 2"));
            assertThat(fake.calls).isEmpty();
        }

        @Test
        void aMaterioneItemNeedsLoadedContentForTakeAndGive() {
            var fake = new PlayerFake();
            var take = new TakeItemAction(ItemCatalog.none());
            var give = new GiveItemAction(ItemCatalog.none());

            assertThat(take.execute(take.parse(PlayerActionsTest.reader(
                    PlayerActionsTest.params("item", "ruby", "amount", 1))), fake.context()))
                    .isEqualTo(ActionOutcome.failed("no content is loaded yet"));
            assertThat(give.execute(give.parse(PlayerActionsTest.reader(
                    PlayerActionsTest.params("item", "ruby"))), fake.context()))
                    .isEqualTo(ActionOutcome.failed("no content is loaded yet"));
            assertThat(fake.calls).isEmpty();
        }

        @Test
        void giveRejectsMalformedVanillaNamesAndAmountsOutsideTheHandOutRange() {
            var give = new GiveItemAction(ItemCatalog.none());
            assertThatThrownBy(() -> give.parse(PlayerActionsTest.reader(
                    PlayerActionsTest.params("item", "minecraft:not a thing"))))
                    .hasMessage("'minecraft:not a thing' is not a vanilla item id");
            assertThatThrownBy(() -> give.parse(PlayerActionsTest.reader(
                    PlayerActionsTest.params("item", "ruby", "amount", 0))))
                    .hasMessageStartingWith("amount must be a whole number in");
        }
    }
}
