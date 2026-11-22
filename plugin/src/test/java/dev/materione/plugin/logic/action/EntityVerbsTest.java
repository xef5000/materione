package dev.materione.plugin.logic.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.Facets;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.ResolvedTarget;
import dev.materione.api.logic.RunMeter;
import dev.materione.api.logic.TargetKind;
import dev.materione.behavior.logic.EngineExpressions;
import dev.materione.behavior.logic.MapLogicContext;
import dev.materione.plugin.logic.action.WorldFakes.Living;
import dev.materione.plugin.logic.action.WorldFakes.Meter;
import dev.materione.plugin.logic.action.WorldFakes.Platform;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The verbs that act on an entity (logic.md §41): {@code heal}, {@code damage}, {@code velocity}, {@code particle} and
 * {@code launch}. Each runs against recording stand-ins, so what is checked is the decision the action makes (the
 * subject, the clamp, the attribution, the maths of a facing, the budget); what the server then does with a real entity
 * is the harness IT and the QA catalog.
 */
class EntityVerbsTest {

    private record Subject(Living living) implements ResolvedTarget {
        @Override public Object anchor() { return living.player; }
        @Override public TargetKind kind() { return TargetKind.ENTITY; }
        @Override public String describe() { return "entity Zed"; }
    }

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

    private static MapLogicContext context(Living runner) {
        return new MapLogicContext(Map.of("player", Map.of("name", "Alex"),
                "damage", Map.of("final", 10d),
                "location", Map.of("x", 10, "y", 64, "z", -4, "world", "world")))
                .with(Facets.PLAYER, runner.player);
    }

    private static MapLogicContext aimedAt(Living runner, Living other) {
        return context(runner).with(ResolvedTarget.FACET, new Subject(other));
    }

    private final Living runner = new Living();
    private final Living zed = new Living(UUID.fromString("00000000-0000-0000-0000-0000000000b0"),
            new Location(null, 5, 70, 5, 90f, 0f));

    @Nested
    class Heal {
        private final HealAction heal = new HealAction(entity -> 20);

        private HealAction healing(Living living) {
            return new HealAction(entity -> living.max);
        }

        @Test
        void restoresHealthAndClampsAtTheMaximum() {
            runner.health = 15;

            assertThat(run(healing(runner), reader("amount", 4), context(runner))).isEqualTo(ActionOutcome.OK);

            assertThat(runner.health).isEqualTo(19);
            assertThat(run(healing(runner), reader("amount", 4), context(runner))).isEqualTo(ActionOutcome.OK);
            assertThat(runner.health).isEqualTo(20); // never past max health
        }

        @Test
        void aFullHealthSubjectOrAnAmountOfZeroIsAHealThatDidNothingNotAFailure() {
            runner.health = 20;
            assertThat(run(healing(runner), reader("amount", 6), context(runner))).isEqualTo(ActionOutcome.OK);
            runner.health = 5;
            assertThat(run(healing(runner), reader("amount", 0), context(runner))).isEqualTo(ActionOutcome.OK);

            assertThat(runner.calls).isEmpty();
        }

        @Test
        void aDeadOrGoneSubjectFails() {
            runner.dead = true;
            assertThat(run(healing(runner), reader("amount", 4), context(runner)))
                    .isEqualTo(ActionOutcome.failed("the subject is dead"));
            runner.dead = false;
            runner.valid = false;
            assertThat(run(healing(runner), reader("amount", 4), context(runner)))
                    .isEqualTo(ActionOutcome.failed("the subject is dead"));
        }

        @Test
        void aHealThatAListenerCancelledFails() {
            runner.health = 5;
            runner.healAccepted = false;

            assertThat(run(healing(runner), reader("amount", 4), context(runner)))
                    .isEqualTo(ActionOutcome.failed("the heal was cancelled"));
        }

        @Test
        void theAmountIsAnExpressionOverTheTriggersFacts() {
            runner.health = 5;

            // lifesteal: 20% of the damage dealt
            assertThat(run(healing(runner), reader("amount", "${damage.final * 0.2}"), context(runner)))
                    .isEqualTo(ActionOutcome.OK);

            assertThat(runner.health).isEqualTo(7);
        }

        @Test
        void anAmountThatIsNotANumberFailsTheStepAndNeverHeals() {
            assertThat(run(healing(runner), reader("amount", "${player.name}"), context(runner)).isOk()).isFalse();
            assertThat(runner.calls).isEmpty();
        }

        @Test
        void aNegativeOrOversizedLiteralIsRejectedAtLoad() {
            assertThatThrownBy(() -> heal.parse(reader("amount", -1))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> heal.parse(reader("amount", 5000))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> heal.parse(reader())).hasMessageContaining("amount is required");
        }

        @Test
        void healsTheTargetAndNotTheRunsPlayer() {
            zed.health = 2;
            runner.health = 2;

            run(healing(zed), reader("amount", 3), aimedAt(runner, zed));

            assertThat(zed.health).isEqualTo(5);
            assertThat(runner.health).isEqualTo(2);
        }

        @Test
        void aRunWithNoPlayerFails() {
            assertThat(run(heal, reader("amount", 1), PlayerFake.noPlayer()))
                    .isEqualTo(ActionOutcome.failed("event has no player"));
        }
    }

    @Nested
    class Damage {
        private final DamageAction damage = new DamageAction();

        @Test
        void isAttributedToTheRunsPlayerWhenTheSubjectIsSomeoneElse() {
            run(damage, reader("amount", 6), aimedAt(runner, zed));

            assertThat(zed.calls).containsExactly("damage:6.0:entity (in scope)");
            assertThat(runner.calls).isEmpty();
        }

        @Test
        void isUnattributedWhenThePlayerHurtsThemselves() {
            run(damage, reader("amount", 4), context(runner));

            assertThat(runner.calls).containsExactly("damage:4.0 (in scope)");
        }

        @Test
        void theScopeEndsWithTheCallSoOnlyTheVerbsOwnEventIsSuppressed() {
            run(damage, reader("amount", 4), aimedAt(runner, zed));

            assertThat(dev.materione.plugin.logic.world.VerbScope.depth()).isZero();
        }

        @Test
        void aDeadSubjectFailsAndZeroIsANoOp() {
            zed.dead = true;
            assertThat(run(damage, reader("amount", 4), aimedAt(runner, zed)))
                    .isEqualTo(ActionOutcome.failed("the subject is dead"));
            zed.dead = false;
            assertThat(run(damage, reader("amount", 0), aimedAt(runner, zed))).isEqualTo(ActionOutcome.OK);
            assertThat(zed.calls).isEmpty();
        }

        @Test
        void aBlowAListenerCancelledIsAFailedStepSaidQuietly() {
            var watched = new DamageAction((victim, blow) -> {
                blow.run();
                return dev.materione.plugin.logic.world.DamageWatch.Result.CANCELLED;
            });

            assertThat(run(watched, reader("amount", 4), aimedAt(runner, zed)))
                    .isEqualTo(ActionOutcome.failed("the damage was cancelled by a listener"));
            assertThat(zed.calls).hasSize(1); // the blow was dealt through the server's own path, then judged
        }

        @Test
        void theEventItRaisesIsAnsweredByEveryRuleButTheOneThatCausedIt() {
            var meter = new Meter();
            var causing = new Object();
            var other = new Object();
            meter.origin = causing;
            var seen = new java.util.ArrayList<java.util.List<Object>>();
            var watched = new DamageAction((victim, blow) -> {
                seen.add(dev.materione.plugin.logic.world.VerbScope.allowed(java.util.List.of(causing, other)));
                blow.run();
                return dev.materione.plugin.logic.world.DamageWatch.Result.LANDED;
            });

            run(watched, reader("amount", 4), aimedAt(runner, zed).with(RunMeter.FACET, meter));

            assertThat(seen).singleElement().satisfies(rules -> assertThat(rules).containsExactly(other));
            assertThat(dev.materione.plugin.logic.world.VerbScope.allowed(java.util.List.of(causing, other)))
                    .containsExactly(causing, other); // after the call everything is answered again
        }

        @Test
        void theAmountIsAnExpression() {
            run(damage, reader("amount", "${damage.final / 2}"), aimedAt(runner, zed));

            assertThat(zed.calls).containsExactly("damage:5.0:entity (in scope)");
        }

        @Test
        void aBadCauseNameIsRejectedAtLoad() {
            assertThat(damage.parse(reader("amount", 1, "cause", "magic")).cause()).isPresent();
            assertThatThrownBy(() -> damage.parse(reader("amount", 1, "cause", "Not A Type")))
                    .hasMessageContaining("not a valid damage type name");
        }
    }

    @Nested
    class Velocity {
        private final VelocityAction velocity = new VelocityAction();

        @Test
        void relativeForwardFollowsTheHeadingAndUpIsWorldUp() {
            // yaw 0 faces +Z in Minecraft
            run(velocity, reader("forward", 1.6, "up", 0.3), context(runner));

            assertThat(runner.velocity.getX()).isEqualTo(0, org.assertj.core.data.Offset.offset(1e-9));
            assertThat(runner.velocity.getY()).isEqualTo(0.3);
            assertThat(runner.velocity.getZ()).isEqualTo(1.6);
        }

        @Test
        void aTurnedSubjectIsPushedAlongItsOwnHeading() {
            // yaw 90 faces -X; the right-hand side of -X is -Z... and of +Z, -X
            run(velocity, reader("forward", 1, "strafe", 2), aimedAt(runner, zed));

            assertThat(zed.velocity.getX()).isEqualTo(-1, org.assertj.core.data.Offset.offset(1e-9));
            assertThat(zed.velocity.getZ()).isEqualTo(-2, org.assertj.core.data.Offset.offset(1e-9));
            assertThat(runner.calls).isEmpty();
        }

        @Test
        void followPitchTakesTheFullLookDirection() {
            zed.location.setPitch(-90f); // straight up
            run(velocity, reader("forward", 2, "follow_pitch", true), aimedAt(runner, zed));

            assertThat(zed.velocity.getY()).isEqualTo(2, org.assertj.core.data.Offset.offset(1e-9));
        }

        @Test
        void absoluteComponentsAreWorldAxesAndAddKeepsTheOldVelocity() {
            runner.velocity = new org.bukkit.util.Vector(1, 1, 1);
            run(velocity, reader("x", 0.5, "y", 2, "mode", "add"), context(runner));

            assertThat(runner.velocity).isEqualTo(new org.bukkit.util.Vector(1.5, 3, 1));
        }

        @Test
        void setReplacesTheVelocity() {
            runner.velocity = new org.bukkit.util.Vector(1, 1, 1);
            run(velocity, reader("y", 2), context(runner));

            assertThat(runner.velocity).isEqualTo(new org.bukkit.util.Vector(0, 2, 0));
        }

        @Test
        void componentsAreExpressions() {
            run(velocity, reader("y", "${damage.final / 10}"), context(runner));

            assertThat(runner.velocity.getY()).isEqualTo(1.0);
        }

        @Test
        void relativeAndAbsoluteCannotBeMixedAndSomethingIsRequired() {
            assertThatThrownBy(() -> velocity.parse(reader("forward", 1, "x", 1)))
                    .hasMessageContaining("not both");
            assertThatThrownBy(() -> velocity.parse(reader("mode", "add")))
                    .hasMessageContaining("needs forward, up or strafe");
            assertThatThrownBy(() -> velocity.parse(reader("forward", 11)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> velocity.parse(reader("forward", 1, "mode", "slide")))
                    .hasMessageContaining("mode must be one of");
        }

        @Test
        void aDeadSubjectFails() {
            runner.dead = true;
            assertThat(run(velocity, reader("up", 1), context(runner)))
                    .isEqualTo(ActionOutcome.failed("the subject is dead"));
        }
    }

    @Nested
    class Particle {
        private final Platform platform = new Platform();

        private ParticleAction particle() {
            return new ParticleAction(platform.verbs);
        }

        private MapLogicContext at(Living living) {
            living.location.setWorld(platform.terrain.world);
            return context(living);
        }

        @Test
        void spawnsAtTheSubjectByDefaultWithCountOffsetAndSpeed() {
            run(particle(), reader("particle", "cloud", "count", 20, "offset", 0.5, "speed", 0.1), at(runner));

            assertThat(platform.terrain.calls).containsExactly(
                    "particle:CLOUD@0.0,64.0,0.0 x20 spread=0.5,0.5,0.5 speed=0.1 data=none");
        }

        @Test
        void aTargetMovesTheTrailToTheTargetsLocation() {
            zed.location.setWorld(platform.terrain.world);
            run(particle(), reader("particle", "cloud"), aimedAt(runner, zed));

            assertThat(platform.terrain.calls).singleElement().asString().contains("@5.0,70.0,5.0");
        }

        @Test
        void anOffsetFromAnAnchorShiftsTheLocation() {
            run(particle(), reader("particle", "cloud", "at", Map.of("of", "player", "y", 1.5)), at(runner));

            assertThat(platform.terrain.calls).singleElement().asString().contains("@0.0,65.5,0.0");
        }

        @Test
        void theEventLocationIsABlocksCentreWhenItsCoordinatesAreWhole() {
            run(particle(), reader("particle", "cloud", "at", "event"), at(runner));

            assertThat(platform.terrain.calls).singleElement().asString().contains("@10.5,64.5,-3.5");
        }

        @Test
        void dustCarriesAColourAndASize() {
            run(particle(), reader("particle", "dust", "color", "#ff0000", "size", 2, "count", 5), at(runner));

            assertThat(platform.terrain.calls).singleElement().asString().contains("data=DustOptions");
        }

        @Test
        void anUnknownParticleFailsAndSpawnsNothing() {
            assertThat(run(particle(), reader("particle", "nonsense"), at(runner)))
                    .isEqualTo(ActionOutcome.failed("unknown particle 'nonsense'"));
            assertThat(platform.terrain.calls).isEmpty();
        }

        @Test
        void aRunsParticleBudgetIsCappedAndSaysSoOncePerInterval() {
            var meter = new Meter();
            var context = at(runner).with(RunMeter.FACET, meter);
            var spawn = particle();
            for (int i = 0; i < 4; i++) {
                assertThat(run(spawn, reader("particle", "cloud", "count", 500), context)).isEqualTo(ActionOutcome.OK);
            }

            // 2,000 spawned: the next step is over the run's budget
            assertThat(run(spawn, reader("particle", "cloud", "count", 1), context).isOk()).isFalse();
            assertThat(run(spawn, reader("particle", "cloud", "count", 1), context).isOk()).isFalse();

            assertThat(platform.terrain.calls).hasSize(4);
            assertThat(platform.diagnostics).singleElement()
                    .satisfies(diagnostic -> assertThat(diagnostic.code()).isEqualTo(DiagnosticCodes.VERB_BUDGET_SPENT));
            platform.now += 11_000;
            run(spawn, reader("particle", "cloud", "count", 1), context);
            assertThat(platform.diagnostics).hasSize(2);
        }

        @Test
        void loadChecksTheNameTheCountTheColourAndTheLocationWord() {
            var action = particle();
            assertThatThrownBy(() -> action.parse(reader("particle", "Cloud!"))).hasMessageContaining("not a valid particle name");
            assertThatThrownBy(() -> action.parse(reader("particle", "cloud", "count", 501))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> action.parse(reader("particle", "dust", "color", "red"))).hasMessageContaining("#rrggbb");
            assertThatThrownBy(() -> action.parse(reader("particle", "cloud", "at", "moon"))).hasMessageContaining("event, player or target");
            assertThatThrownBy(() -> action.parse(reader("particle", "cloud", "at", Map.of("of", "player", "w", 1))))
                    .hasMessageContaining("no key 'w'");
            assertThatThrownBy(() -> action.parse(reader("particle", "cloud", "offset", java.util.List.of(1, 2))))
                    .hasMessageContaining("one number or three");
        }
    }

    @Nested
    class Launch {
        private final Platform platform = new Platform();

        private LaunchAction launch() {
            return new LaunchAction(platform.verbs);
        }

        @Test
        void shootsFromTheSubjectAlongItsAimAtTheSpeed() {
            assertThat(run(launch(), reader("projectile", "small_fireball", "speed", 1.5), context(runner)))
                    .isEqualTo(ActionOutcome.OK);

            assertThat(platform.launcher.launches).containsExactly("SMALL_FIREBALL speed=1.5 item=-");
        }

        @Test
        void countFiresSeveralEachWithItsOwnSpreadButTheSameSpeed() {
            run(launch(), reader("projectile", "snowball", "speed", 2, "spread", 10, "count", 3), context(runner));

            assertThat(platform.launcher.launches).hasSize(3).allSatisfy(line -> assertThat(line).contains("speed=2.0"));
        }

        @Test
        void aTargetIsTheShooter() {
            assertThat(run(launch(), reader("projectile", "snowball"), aimedAt(runner, zed))).isEqualTo(ActionOutcome.OK);
            assertThat(platform.launcher.launches).hasSize(1);
        }

        @Test
        void anUnknownOrNonProjectileTypeFailsAndRefusalFails() {
            assertThat(run(launch(), reader("projectile", "zombie"), context(runner)).isOk()).isFalse();
            platform.launcher.refuse = true;
            assertThat(run(launch(), reader("projectile", "snowball"), context(runner)))
                    .isEqualTo(ActionOutcome.failed("the launch was refused"));
        }

        @Test
        void aRunsLaunchesAreCappedAtSixteenAndThenFailWithTheDiagnostic() {
            var meter = new Meter();
            var context = context(runner).with(RunMeter.FACET, meter);
            assertThat(run(launch(), reader("projectile", "snowball", "count", 16), context)).isEqualTo(ActionOutcome.OK);

            assertThat(run(launch(), reader("projectile", "snowball"), context).isOk()).isFalse();

            assertThat(platform.launcher.launches).hasSize(16);
            assertThat(platform.diagnostics).singleElement()
                    .satisfies(diagnostic -> assertThat(diagnostic.code()).isEqualTo(DiagnosticCodes.VERB_BUDGET_SPENT));
        }

        @Test
        void loadChecksTheRanges() {
            assertThatThrownBy(() -> launch().parse(reader("projectile", "snowball", "count", 17)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> launch().parse(reader("projectile", "snowball", "speed", 11)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> launch().parse(reader("projectile", "Snow Ball"))).hasMessageContaining("not a valid entity type");
        }

        @Test
        void theConeKeepsEveryShotWithinTheSpread() {
            var aim = new org.bukkit.util.Vector(0, 0, 1);
            for (int i = 0; i < 200; i++) {
                var shot = LaunchAction.cone(aim, 10);
                assertThat(shot.length()).isEqualTo(1, org.assertj.core.data.Offset.offset(1e-6));
                assertThat(Math.toDegrees(shot.angle(aim))).isLessThanOrEqualTo(10.0001);
            }
        }
    }
}
