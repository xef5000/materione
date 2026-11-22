package dev.materione.plugin.logic.action;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.Facets;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.ResolvedTarget;
import dev.materione.api.logic.TargetKind;
import dev.materione.api.protection.ProtectionMode;
import dev.materione.behavior.logic.EngineExpressions;
import dev.materione.behavior.logic.MapLogicContext;
import dev.materione.plugin.logic.action.WorldFakes.FakePort;
import dev.materione.plugin.logic.action.WorldFakes.Living;
import dev.materione.plugin.logic.action.WorldFakes.Platform;
import dev.materione.plugin.protection.ProtectionSettings;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The protection port in the world verbs (logic.md §43, protection.md sections 4 and 8): what {@code break_blocks},
 * {@code set_block}, {@code damage} and {@code launch} ask the port in each {@code protection_mode}, which of the event path
 * and the port runs, what {@code as_player: false} asks, and the Folia boundary. The stand-in provider records every question;
 * that a real protection plugin answers is the adapters' tests and the harness IT.
 */
class ProtectionVerbsTest {

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

    private static final ProtectionSettings BOTH = new ProtectionSettings(ProtectionMode.BOTH, true);
    private static final ProtectionSettings PORT = new ProtectionSettings(ProtectionMode.PORT, true);
    private static final ProtectionSettings EVENT = new ProtectionSettings(ProtectionMode.EVENT, true);

    private final Living runner = new Living();
    private final Living zed = new Living(UUID.fromString("00000000-0000-0000-0000-0000000000b0"),
            new Location(null, 5, 70, 5, 90f, 0f));

    private record Subject(Living living) implements ResolvedTarget {
        @Override public Object anchor() { return living.player; }
        @Override public TargetKind kind() { return TargetKind.ENTITY; }
        @Override public String describe() { return "entity Zed"; }
    }

    /** A platform in {@code settings} with the stand-in provider registered. */
    private static Platform platform(ProtectionSettings settings, FakePort port) {
        var platform = new Platform(settings);
        platform.protection.register(WorldFakes.enabledPlugin(), port);
        return platform;
    }

    private MapLogicContext event(Platform platform) {
        runner.location.setWorld(platform.terrain.world);
        return new MapLogicContext(Map.of("player", Map.of("name", "Alex"),
                "location", Map.of("x", 10, "y", 64, "z", 10, "world", "world")))
                .with(Facets.PLAYER, runner.player);
    }

    private MapLogicContext playerless() {
        return new MapLogicContext(Map.of("location", Map.of("x", 10, "y", 64, "z", 10, "world", "world")));
    }

    @Nested
    class BreakBlocks {

        @Test
        void inBothModeAPortDenialStopsTheBlockBeforeTheEventPathRuns() {
            var port = new FakePort();
            port.denied.add("11,64,10");
            var platform = platform(BOTH, port);
            platform.terrain.fill(9, 64, 10, 11, 64, 10, Material.STONE);

            assertThat(run(new BreakBlocksAction(platform.verbs), reader("shape", List.of(1, 0, 0)), event(platform)))
                    .isEqualTo(ActionOutcome.OK);

            // the denied block was asked and never reached the player's own break path; the others were asked, then broken
            assertThat(port.asked).contains("BREAK@11,64,10 by player").hasSize(3);
            assertThat(platform.blocks.calls).containsExactlyInAnyOrder("breakAs:10,64,10", "breakAs:9,64,10");
            assertThat(platform.terrain.types).containsOnlyKeys("11,64,10");
        }

        @Test
        void aStepWhereEveryBlockIsDeniedFailsAndSaysWhichProviderRefused() {
            var port = new FakePort();
            port.denied.add("10,64,10");
            var platform = platform(BOTH, port);
            platform.terrain.fill(10, 64, 10, 10, 64, 10, Material.STONE);

            assertThat(run(new BreakBlocksAction(platform.verbs), reader(), event(platform))).isEqualTo(
                    ActionOutcome.failed("no block was broken (1 refused by Stand-in (the stand-in says no), "
                            + "0 in another region, 0 could not be broken)"));
            assertThat(platform.blocks.calls).isEmpty();
        }

        @Test
        void inEventModeThePortIsNotAskedAndTheEventPathDecidesAlone() {
            var port = new FakePort();
            port.denied.add("10,64,10");
            var platform = platform(EVENT, port);
            platform.terrain.fill(10, 64, 10, 10, 64, 10, Material.STONE);

            assertThat(run(new BreakBlocksAction(platform.verbs), reader(), event(platform))).isEqualTo(ActionOutcome.OK);

            assertThat(port.asked).isEmpty();
            assertThat(platform.blocks.calls).containsExactly("breakAs:10,64,10");
        }

        @Test
        void inPortModeThePortAnswersAloneAndNoBreakEventIsRaised() {
            var port = new FakePort();
            port.denied.add("11,64,10");
            var platform = platform(PORT, port);
            platform.terrain.fill(9, 64, 10, 11, 64, 10, Material.STONE);

            assertThat(run(new BreakBlocksAction(platform.verbs), reader("shape", List.of(1, 0, 0)), event(platform)))
                    .isEqualTo(ActionOutcome.OK);

            assertThat(platform.blocks.calls).containsExactlyInAnyOrder("breakWith:10,64,10", "breakWith:9,64,10");
            assertThat(platform.terrain.types).containsOnlyKeys("11,64,10");
        }

        @Test
        void asPlayerFalseAsksThePortWithNoActorAndADenialStopsTheBlock() {
            var port = new FakePort();
            port.denied.add("11,64,10");
            var platform = platform(BOTH, port);
            platform.terrain.fill(9, 64, 10, 11, 64, 10, Material.STONE);

            assertThat(run(new BreakBlocksAction(platform.verbs),
                    reader("shape", List.of(1, 0, 0), "as_player", false), playerless())).isEqualTo(ActionOutcome.OK);

            assertThat(port.asked).hasSize(3).allSatisfy(line -> assertThat(line).endsWith("by nobody"));
            assertThat(platform.blocks.calls).containsExactlyInAnyOrder("breakPlain:10,64,10", "breakPlain:9,64,10");
        }

        @Test
        void asPlayerFalseAsksNobodyInEventModeAsItAlwaysDid() {
            var port = new FakePort();
            port.denied.add("10,64,10");
            var platform = platform(EVENT, port);
            platform.terrain.fill(10, 64, 10, 10, 64, 10, Material.STONE);

            run(new BreakBlocksAction(platform.verbs), reader("as_player", false), playerless());

            assertThat(port.asked).isEmpty();
            assertThat(platform.blocks.calls).containsExactly("breakPlain:10,64,10");
        }

        @Test
        void theNoActorSwitchLetsAnActorlessChangeThroughUnasked() {
            var port = new FakePort();
            port.denied.add("10,64,10");
            var platform = platform(new ProtectionSettings(ProtectionMode.BOTH, false), port);
            platform.terrain.fill(10, 64, 10, 10, 64, 10, Material.STONE);

            run(new BreakBlocksAction(platform.verbs), reader("as_player", false), playerless());

            assertThat(port.asked).isEmpty();
            assertThat(platform.blocks.calls).containsExactly("breakPlain:10,64,10");
        }

        @Test
        void aForeignBlockAsThePlayerStaysRefusedInBothModeBecauseTheEventPathNeedsThePlayersThread() {
            var platform = platform(BOTH, new FakePort());
            platform.terrain.fill(9, 64, 10, 11, 64, 10, Material.STONE);
            platform.regions.foreign.add("11,64,10");

            run(new BreakBlocksAction(platform.verbs), reader("shape", List.of(1, 0, 0)), event(platform));

            assertThat(platform.blocks.calls).containsExactlyInAnyOrder("breakAs:10,64,10", "breakAs:9,64,10");
            assertThat(platform.diagnostics).singleElement()
                    .satisfies(d -> assertThat(d.code()).isEqualTo(DiagnosticCodes.VERB_REGION_REFUSED));
        }

        @Test
        void aForeignBlockAsThePlayerIsReachedOnItsOwnRegionInPortMode() {
            var port = new FakePort();
            var platform = platform(PORT, port);
            platform.terrain.fill(9, 64, 10, 11, 64, 10, Material.STONE);
            platform.regions.foreign.add("11,64,10");

            assertThat(run(new BreakBlocksAction(platform.verbs), reader("shape", List.of(1, 0, 0)), event(platform)))
                    .isEqualTo(ActionOutcome.OK);

            assertThat(platform.regions.hops).containsExactly("11,64,10"); // that block's own region ran the question and the break
            assertThat(platform.blocks.calls).hasSize(3).allMatch(call -> call.startsWith("breakWith:"));
            assertThat(port.asked).contains("BREAK@11,64,10 by player");
            assertThat(platform.diagnostics).isEmpty(); // no MTR-0119: the refusal is lifted where the port answers alone
        }
    }

    @Nested
    class SetBlock {

        @Test
        void inBothModeThePortIsAskedBeforeThePlacementEvent() {
            var port = new FakePort();
            var platform = platform(BOTH, port);

            assertThat(run(new SetBlockAction(platform.verbs), reader("block", "stone"), event(platform)))
                    .isEqualTo(ActionOutcome.OK);

            assertThat(port.asked).containsExactly("PLACE@10,64,10 by player");
            assertThat(platform.blocks.calls).containsExactly("mayPlace:10,64,10", "place:10,64,10");
        }

        @Test
        void aPortDenialStopsThePlacementBeforeTheEventIsEverRaised() {
            var port = new FakePort();
            port.denied.add("10,64,10");
            var platform = platform(BOTH, port);

            assertThat(run(new SetBlockAction(platform.verbs), reader("block", "stone"), event(platform))).isEqualTo(
                    ActionOutcome.failed("the placement was refused by Stand-in (the stand-in says no)"));

            assertThat(platform.blocks.calls).isEmpty();
        }

        @Test
        void inPortModeThePlacementEventIsNotRaised() {
            var platform = platform(PORT, new FakePort());

            run(new SetBlockAction(platform.verbs), reader("block", "stone"), event(platform));

            assertThat(platform.blocks.calls).containsExactly("place:10,64,10");
        }

        @Test
        void inEventModeThePortIsNotAsked() {
            var port = new FakePort();
            port.denied.add("10,64,10");
            var platform = platform(EVENT, port);

            assertThat(run(new SetBlockAction(platform.verbs), reader("block", "stone"), event(platform)))
                    .isEqualTo(ActionOutcome.OK);

            assertThat(port.asked).isEmpty();
        }

        @Test
        void asPlayerFalseAsksThePortWithNoActor() {
            var port = new FakePort();
            port.denied.add("10,64,10");
            var platform = platform(BOTH, port);

            assertThat(run(new SetBlockAction(platform.verbs), reader("block", "stone", "as_player", false), playerless()))
                    .isEqualTo(ActionOutcome.failed("the placement was refused by Stand-in (the stand-in says no)"));

            assertThat(port.asked).containsExactly("PLACE@10,64,10 by nobody");
            assertThat(platform.blocks.calls).isEmpty();
        }

        @Test
        void aForeignBlockIsRefusedInBothModeAndReachedOnItsRegionInPortMode() {
            var both = platform(BOTH, new FakePort());
            both.regions.foreign.add("10,64,10");
            assertThat(run(new SetBlockAction(both.verbs), reader("block", "stone"), event(both)).isOk()).isFalse();
            assertThat(both.diagnostics).singleElement()
                    .satisfies(d -> assertThat(d.code()).isEqualTo(DiagnosticCodes.VERB_REGION_REFUSED));

            var port = platform(PORT, new FakePort());
            port.regions.foreign.add("10,64,10");
            assertThat(run(new SetBlockAction(port.verbs), reader("block", "stone"), event(port)))
                    .isEqualTo(ActionOutcome.OK);
            assertThat(port.regions.hops).containsExactly("10,64,10");
            assertThat(port.diagnostics).isEmpty();
        }

        @Test
        void aMateroneBlockAsksThePortToo() {
            var port = new FakePort();
            port.denied.add("10,64,10");
            var platform = platform(BOTH, port);
            platform.verbs.bindCustomBlocks(new WorldFakes.FakeCustomBlocks());

            assertThat(run(new SetBlockAction(platform.verbs), reader("block", "pack:ruby_ore"), event(platform)).isOk())
                    .isFalse();
        }
    }

    @Nested
    class DamageAndLaunch {

        private MapLogicContext aimedAt(Living attacker, Living victim, Platform platform) {
            attacker.location.setWorld(platform.terrain.world);
            victim.location.setWorld(platform.terrain.world);
            return new MapLogicContext(Map.of("player", Map.of("name", "Alex")))
                    .with(Facets.PLAYER, attacker.player).with(ResolvedTarget.FACET, new Subject(victim));
        }

        @Test
        void aPvpDenialStopsTheBlowBeforeItIsDealt() {
            var port = new FakePort();
            port.denied.add("5,70,5");
            var platform = platform(BOTH, port);

            assertThat(run(new DamageAction(dev.materione.plugin.logic.world.DamageWatch.NONE, platform.protection),
                    reader("amount", 6), aimedAt(runner, zed, platform))).isEqualTo(
                            ActionOutcome.failed("the damage was refused by Stand-in (the stand-in says no)"));

            assertThat(zed.calls).isEmpty();
            assertThat(port.asked).containsExactly("DAMAGE@5,70,5 by player");
        }

        @Test
        void aBlowThePortAllowsIsDealtAsAlways() {
            var port = new FakePort();
            var platform = platform(BOTH, port);

            run(new DamageAction(dev.materione.plugin.logic.world.DamageWatch.NONE, platform.protection),
                    reader("amount", 6), aimedAt(runner, zed, platform));

            assertThat(zed.calls).containsExactly("damage:6.0:entity (in scope)");
        }

        @Test
        void aPlayerHurtingThemselvesIsNotAPvpQuestion() {
            var port = new FakePort();
            port.denied.add("0,64,0");
            var platform = platform(BOTH, port);

            run(new DamageAction(dev.materione.plugin.logic.world.DamageWatch.NONE, platform.protection),
                    reader("amount", 4), new MapLogicContext(Map.of()).with(Facets.PLAYER, runner.player));

            assertThat(port.asked).isEmpty();
            assertThat(runner.calls).hasSize(1);
        }

        @Test
        void inEventModeDamageAsksNoPort() {
            var port = new FakePort();
            port.denied.add("5,70,5");
            var platform = platform(EVENT, port);

            run(new DamageAction(dev.materione.plugin.logic.world.DamageWatch.NONE, platform.protection),
                    reader("amount", 6), aimedAt(runner, zed, platform));

            assertThat(port.asked).isEmpty();
            assertThat(zed.calls).hasSize(1);
        }

        @Test
        void aLaunchAsksTheUseQuestionAtTheShootersEyes() {
            var port = new FakePort();
            var platform = platform(BOTH, port);
            runner.location.setWorld(platform.terrain.world);

            assertThat(run(new LaunchAction(platform.verbs), reader("projectile", "snowball"),
                    new MapLogicContext(Map.of()).with(Facets.PLAYER, runner.player))).isEqualTo(ActionOutcome.OK);

            assertThat(port.asked).containsExactly("USE@0,65,0 by player");
        }

        @Test
        void aRefusedLaunchLaunchesNothing() {
            var port = new FakePort();
            port.denied.add("USE@0,65,0");
            var platform = platform(BOTH, port);
            runner.location.setWorld(platform.terrain.world);

            assertThat(run(new LaunchAction(platform.verbs), reader("projectile", "snowball"),
                    new MapLogicContext(Map.of()).with(Facets.PLAYER, runner.player))).isEqualTo(
                            ActionOutcome.failed("the launch was refused by Stand-in (the stand-in says no)"));

            assertThat(platform.launcher.launches).isEmpty();
        }
    }
}
