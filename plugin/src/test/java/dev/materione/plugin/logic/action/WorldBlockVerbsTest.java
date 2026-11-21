package dev.materione.plugin.logic.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.Facets;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.RunMeter;
import dev.materione.behavior.logic.EngineExpressions;
import dev.materione.behavior.logic.MapLogicContext;
import dev.materione.plugin.logic.action.WorldFakes.FakeCustomBlocks;
import dev.materione.plugin.logic.action.WorldFakes.Living;
import dev.materione.plugin.logic.action.WorldFakes.Meter;
import dev.materione.plugin.logic.action.WorldFakes.Platform;
import dev.materione.plugin.logic.world.VerbScope;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The verbs that change the world (logic.md §41): {@code break_blocks}, {@code set_block} and {@code drop_item}. The box,
 * the protection veto, the budget, the regions and the as-player rule are decided by the actions and are checked here
 * against scripted seams; that a real {@code BlockBreakEvent} reaches a protection plugin is the harness IT.
 */
class WorldBlockVerbsTest {

    private static ParamReader reader(Object... keyValues) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return ParamReader.of(map, EngineExpressions.INSTANCE);
    }

    private static <P> ActionOutcome run(ActionType<P> action, ParamReader params, LogicContext context) {
        return action.execute(action.parse(params), context);
    }

    private final Platform platform = new Platform();
    private final Living runner = new Living();

    /** The run of a player whose trigger happened at the block 10, 64, 10 (whole numbers: a block's coordinates). */
    private MapLogicContext event() {
        runner.location.setWorld(platform.terrain.world);
        return new MapLogicContext(Map.of("player", Map.of("name", "Alex"),
                "level", 3, "kind", "stone",
                "location", Map.of("x", 10, "y", 64, "z", 10, "world", "world")))
                .with(Facets.PLAYER, runner.player);
    }

    /** A run with no player, as an explosion's. */
    private MapLogicContext playerless() {
        return new MapLogicContext(Map.of("location", Map.of("x", 10, "y", 64, "z", 10, "world", "world")));
    }

    @Nested
    class BreakBlocks {
        private final BreakBlocksAction action = new BreakBlocksAction(platform.verbs);

        @Test
        void aThreeByThreeBoxBreaksTwentySevenBlocksAsThePlayer() {
            platform.terrain.fill(9, 63, 9, 11, 65, 11, Material.STONE);

            assertThat(run(action, reader("shape", List.of(1, 1, 1)), event())).isEqualTo(ActionOutcome.OK);

            assertThat(platform.blocks.calls).hasSize(27).allMatch(call -> call.startsWith("breakAs:"));
            assertThat(platform.terrain.types).isEmpty();
        }

        @Test
        void aProtectedBlockSurvivesAndTheRestAreBroken() {
            platform.terrain.fill(9, 63, 9, 11, 65, 11, Material.STONE);
            platform.blocks.protectedBlocks.add("11,64,10");

            assertThat(run(action, reader("shape", List.of(1, 1, 1)), event())).isEqualTo(ActionOutcome.OK);

            assertThat(platform.terrain.types).containsOnlyKeys("11,64,10");
        }

        @Test
        void theNearestBlocksComeFirstSoACutShortStepKeepsTheMiddle() {
            platform.terrain.fill(9, 63, 9, 11, 65, 11, Material.STONE);

            run(action, reader("shape", 1), event());

            assertThat(platform.blocks.calls.get(0)).isEqualTo("breakAs:10,64,10");
        }

        @Test
        @SuppressWarnings("try")
        void theBlockOfTheBreakEventIsLeftToVanilla() {
            platform.terrain.fill(9, 64, 9, 11, 64, 11, Material.STONE);

            try (var scope = VerbScope.breakEvent(platform.terrain.block(10, 64, 10))) {
                assertThat(run(action, reader("shape", List.of(1, 0, 1)), event())).isEqualTo(ActionOutcome.OK);
            }

            assertThat(platform.blocks.calls).hasSize(8).doesNotContain("breakAs:10,64,10");
            assertThat(platform.terrain.types).containsOnlyKeys("10,64,10");
        }

        @Test
        void airAndUnbreakableBlocksAreSkippedAndAnEmptyBoxIsNotAFailure() {
            platform.terrain.types.put("10,64,10", Material.BEDROCK);
            platform.blocks.unbreakable.add("10,64,10");

            assertThat(run(action, reader("shape", 1), event())).isEqualTo(ActionOutcome.OK);

            assertThat(platform.blocks.calls).isEmpty();
        }

        @Test
        void whenEveryEligibleBlockIsRefusedTheStepFailsWithTheCounts() {
            platform.terrain.fill(10, 64, 10, 10, 64, 10, Material.STONE);
            platform.blocks.protectedBlocks.add("10,64,10");

            assertThat(run(action, reader(), event())).isEqualTo(ActionOutcome.failed(
                    "no block was broken (1 refused by a listener, 0 in another region, 0 could not be broken)"));
        }

        @Test
        void dropsFalseIsPassedOn() {
            platform.terrain.fill(10, 64, 10, 10, 64, 10, Material.STONE);

            run(action, reader("drops", false), event());

            assertThat(platform.blocks.calls).containsExactly("breakAs:10,64,10:nodrops");
        }

        @Test
        void asPlayerNeedsAPlayerAndAsPlayerFalseWorksWithout() {
            platform.terrain.fill(10, 64, 10, 10, 64, 10, Material.STONE);

            assertThat(run(action, reader(), playerless()).isOk()).isFalse();
            assertThat(platform.blocks.calls).isEmpty();

            assertThat(run(action, reader("as_player", false), playerless())).isEqualTo(ActionOutcome.OK);
            assertThat(platform.blocks.calls).containsExactly("breakPlain:10,64,10");
        }

        @Test
        void aBlockInAnotherRegionIsSkippedAsThePlayerAndHoppedToOtherwise() {
            platform.terrain.fill(9, 64, 10, 11, 64, 10, Material.STONE);
            platform.regions.foreign.add("11,64,10");

            run(action, reader("shape", List.of(1, 0, 0)), event());

            assertThat(platform.blocks.calls).containsExactlyInAnyOrder("breakAs:10,64,10", "breakAs:9,64,10");
            assertThat(platform.diagnostics).singleElement()
                    .satisfies(d -> assertThat(d.code()).isEqualTo(DiagnosticCodes.VERB_REGION_REFUSED));

            platform.terrain.fill(9, 64, 10, 11, 64, 10, Material.STONE);
            platform.blocks.calls.clear();
            run(action, reader("shape", List.of(1, 0, 0), "as_player", false), playerless());

            assertThat(platform.regions.hops).containsExactly("11,64,10"); // that block's own region ran it
            assertThat(platform.blocks.calls).hasSize(3).allMatch(call -> call.startsWith("breakPlain:"));
        }

        @Test
        void aChunkThatIsNotLoadedIsNeverLoadedForTheStep() {
            platform.terrain.fill(10, 64, 10, 10, 64, 10, Material.STONE);
            platform.terrain.unloadedChunks.add("0,0");

            assertThat(run(action, reader(), event())).isEqualTo(ActionOutcome.OK);

            assertThat(platform.blocks.calls).isEmpty();
        }

        @Test
        void aRunsBlocksAreCappedAndTheOverrunningStepDoesNothing() {
            var meter = new Meter();
            var context = event().with(RunMeter.FACET, meter);
            assertThat(run(action, reader("shape", 4), context)).isEqualTo(ActionOutcome.OK); // 729
            assertThat(run(action, reader("shape", 4), context)).isEqualTo(ActionOutcome.OK); // 1,458

            platform.terrain.fill(6, 60, 6, 14, 68, 14, Material.STONE);
            assertThat(run(action, reader("shape", 4), context).isOk()).isFalse(); // 2,187 is over 2,048

            assertThat(platform.blocks.calls).isEmpty();
            assertThat(platform.diagnostics).singleElement()
                    .satisfies(d -> assertThat(d.code()).isEqualTo(DiagnosticCodes.VERB_BUDGET_SPENT));
        }

        @Test
        void theShapeMayBeAMappingWithTemplatesAndIsBoundedWhateverItsSpelling() {
            platform.terrain.fill(8, 64, 10, 12, 64, 10, Material.STONE);

            assertThat(run(action, reader("shape", Map.of("x", "${level - 1}")), event())).isEqualTo(ActionOutcome.OK);
            assertThat(platform.blocks.calls).hasSize(5); // level 3: radius 2 on x only

            assertThat(run(action, reader("shape", Map.of("x", "${level + 9}")), event()).isOk()).isFalse();
        }

        @Test
        void loadRejectsAShapePastTheCapAndAnUnknownAtAndAnAtTarget() {
            assertThatThrownBy(() -> action.parse(reader("shape", 5))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> action.parse(reader("shape", List.of(1, 1)))).hasMessageContaining("one number or three");
            assertThatThrownBy(() -> action.parse(reader("shape", Map.of("w", 1)))).hasMessageContaining("no axis 'w'");
            assertThatThrownBy(() -> action.parse(reader("at", "target"))).hasMessageContaining("this action has no target");
            assertThatThrownBy(() -> action.parse(reader("shape", 1.5))).isInstanceOf(IllegalArgumentException.class);
            assertThat(action.parse(reader("shape", 4)).asPlayer()).isTrue(); // the defaults ask protection
        }
    }

    @Nested
    class SetBlock {
        private final SetBlockAction action = new SetBlockAction(platform.verbs);

        @Test
        void asksThePlayersPlacementPathFirstAndThenPlaces() {
            assertThat(run(action, reader("block", "stone"), event())).isEqualTo(ActionOutcome.OK);

            assertThat(platform.blocks.calls).containsExactly("mayPlace:10,64,10", "place:10,64,10");
        }

        @Test
        void aListenersVetoStopsThePlacement() {
            platform.blocks.mayPlace = false;

            assertThat(run(action, reader("block", "stone"), event()))
                    .isEqualTo(ActionOutcome.failed("the placement was refused by a listener"));

            assertThat(platform.blocks.calls).containsExactly("mayPlace:10,64,10");
        }

        @Test
        void asPlayerFalseAsksNobodyAndWorksWithoutAPlayer() {
            assertThat(run(action, reader("block", "stone"), playerless()).isOk()).isFalse();

            assertThat(run(action, reader("block", "stone", "as_player", false), playerless())).isEqualTo(ActionOutcome.OK);
            assertThat(platform.blocks.calls).containsExactly("place:10,64,10");
        }

        @Test
        void replaceAirRefusesAnOccupiedBlock() {
            platform.terrain.types.put("10,64,10", Material.DIRT);

            assertThat(run(action, reader("block", "stone", "replace", "air"), event()))
                    .isEqualTo(ActionOutcome.failed("the block is occupied"));
            assertThat(platform.blocks.calls).isEmpty();
        }

        @Test
        void anOffsetPlacesElsewhere() {
            run(action, reader("block", "glass", "at", Map.of("of", "event", "y", -1)), event());

            assertThat(platform.blocks.calls).contains("place:10,63,10");
        }

        @Test
        void aMateroneBlockIdGoesThroughTheCustomBlockRuntime() {
            var custom = new FakeCustomBlocks();
            platform.verbs.bindCustomBlocks(custom);

            assertThat(run(action, reader("block", "pack:ruby_ore"), event())).isEqualTo(ActionOutcome.OK);
            assertThat(custom.calls).containsExactly("place:pack:ruby_ore");
            assertThat(run(action, reader("block", "pack:missing"), event()).isOk()).isFalse();
        }

        @Test
        void aMateroneBlockBeforeContentIsLoadedFailsSoft() {
            assertThat(run(action, reader("block", "pack:ruby_ore"), event()))
                    .isEqualTo(ActionOutcome.failed("no Materione block 'pack:ruby_ore' is loaded"));
        }

        @Test
        void aNonBlockFailsAndTheBlockMayBeAnExpression() {
            assertThat(run(action, reader("block", "nonsense"), event()))
                    .isEqualTo(ActionOutcome.failed("'nonsense' is not a block"));
            assertThat(run(action, reader("block", "${kind}"), event())).isEqualTo(ActionOutcome.OK);
        }

        @Test
        void aBlockInAnotherRegionIsRefusedAsThePlayer() {
            platform.regions.foreign.add("10,64,10");

            assertThat(run(action, reader("block", "stone"), event()).isOk()).isFalse();

            assertThat(platform.blocks.calls).isEmpty();
            assertThat(platform.diagnostics).singleElement()
                    .satisfies(d -> assertThat(d.code()).isEqualTo(DiagnosticCodes.VERB_REGION_REFUSED));
        }

        @Test
        void sharesTheBlockBudgetWithBreakBlocks() {
            var meter = new Meter();
            meter.add("world.blocks", 2_048);
            var context = event().with(RunMeter.FACET, meter);

            assertThat(run(action, reader("block", "stone"), context).isOk()).isFalse();

            assertThat(platform.blocks.calls).isEmpty();
            assertThat(platform.diagnostics).hasSize(1);
        }

        @Test
        void aBlockIdWithAnUnknownKeyOrWordIsRejectedAtLoad() {
            assertThatThrownBy(() -> action.parse(reader("block", "stone", "replace", "water")))
                    .hasMessageContaining("replace must be one of");
            assertThatThrownBy(() -> action.parse(reader())).hasMessageContaining("block is required");
        }
    }

    @Nested
    class DropItem {
        private final DropItemAction action = new DropItemAction(dev.materione.plugin.logic.support.ItemCatalog.none(),
                platform.verbs);

        @Test
        void anItemThatIsNotLoadedFailsSoftAndDropsNothing() {
            assertThat(run(action, reader("item", "pack:ruby"), event()))
                    .isEqualTo(ActionOutcome.failed("no content is loaded yet"));

            assertThat(platform.terrain.calls).isEmpty();
        }

        @Test
        void loadChecksTheItemTheCountAndTheLocation() {
            assertThatThrownBy(() -> action.parse(reader("item", "ruby", "amount", 0))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> action.parse(reader("item", "ruby", "at", "target"))).hasMessageContaining("no target");
            assertThat(action.parse(reader("item", "ruby", "at", "player")).at().anchor())
                    .isEqualTo(dev.materione.plugin.logic.world.LocationSpec.Anchor.PLAYER);
            assertThat(action.parse(reader("item", "ruby")).at().anchor())
                    .isEqualTo(dev.materione.plugin.logic.world.LocationSpec.Anchor.EVENT);
        }

        @Test
        void aRunWithoutALocationFailsBeforeAnythingDrops() {
            var context = new MapLogicContext(Map.of());

            assertThat(run(action, reader("item", "pack:ruby"), context))
                    .isEqualTo(ActionOutcome.failed("this event carries no location"));
        }
    }
}
