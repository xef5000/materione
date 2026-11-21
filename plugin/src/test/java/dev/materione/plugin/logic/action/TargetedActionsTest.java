package dev.materione.plugin.logic.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.Facets;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.ResolvedTarget;
import dev.materione.api.logic.Subjects;
import dev.materione.api.logic.TargetKind;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.behavior.logic.EngineExpressions;
import dev.materione.behavior.logic.MapLogicContext;
import dev.materione.plugin.logic.BuiltinLogic;
import dev.materione.plugin.logic.support.ItemCatalog;
import dev.materione.plugin.logic.action.ProviderActionsTest.FakePermissions;
import dev.materione.plugin.logic.action.ProviderActionsTest.FakeProviders;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The first targetable actions (logic.md §39) act on the target and not on the run's own player: each is given a
 * context that holds both, and the effect lands on the target's stand-in only. The declarations are pinned too, because
 * they are what {@code logic.vocabulary}, the editor and the SDK read.
 */
class TargetedActionsTest {

    /** A resolved target whose anchor is a recording stand-in for a player. */
    private record Subject(PlayerFake fake, UUID id) implements ResolvedTarget {
        @Override
        public Object anchor() {
            return fake.player;
        }

        @Override
        public TargetKind kind() {
            return TargetKind.PLAYER;
        }

        @Override
        public String describe() {
            return "player Bob";
        }
    }

    private static ParamReader reader(Object... keyValues) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return ParamReader.of(map, EngineExpressions.INSTANCE);
    }

    private final PlayerFake runner = new PlayerFake();
    private final PlayerFake bob = new PlayerFake(UUID.fromString("00000000-0000-0000-0000-0000000000b0"));

    /** The run's player is Alex; the step acts on Bob. */
    private MapLogicContext aimedAtBob() {
        return new MapLogicContext(Map.of("player", Map.of("name", "Alex")))
                .with(Facets.PLAYER, runner.player)
                .with(ResolvedTarget.FACET, new Subject(bob, bob.uuid))
                .withPlaceholders(text -> text.replace("%server%", "Lobby"));
    }

    private static <P> ActionOutcome run(ActionType<P> action, ParamReader params, LogicContext context) {
        return action.execute(action.parse(params), context);
    }

    @Test
    void subjectsAreTheTargetWhenThereIsOneAndTheRunsPlayerOtherwise() {
        var context = aimedAtBob();
        assertThat(Subjects.player(context)).containsSame(bob.player);
        assertThat(Subjects.entity(context)).containsSame(bob.player);
        assertThat(Subjects.targeted(context)).isTrue();

        var plain = runner.context();
        assertThat(Subjects.player(plain)).containsSame(runner.player);
        assertThat(Subjects.targeted(plain)).isFalse();
        assertThat(Subjects.player(PlayerFake.noPlayer())).isEmpty();
    }

    @Test
    void messageActionbarTitleAndSoundReachTheTargetAndNotTheRunsPlayer() {
        assertThat(run(new MessageAction(), reader("text", "${player.name} says hi"), aimedAtBob())).isEqualTo(ActionOutcome.OK);
        assertThat(run(new ActionbarAction(), reader("text", "bar"), aimedAtBob())).isEqualTo(ActionOutcome.OK);
        assertThat(run(new TitleAction(), reader("title", "T", "subtitle", "S"), aimedAtBob())).isEqualTo(ActionOutcome.OK);
        assertThat(run(new SoundAction(), reader("sound", "entity.player.levelup"), aimedAtBob())).isEqualTo(ActionOutcome.OK);

        // the sender's name is still the sender's (the expression is the run's own); the effect is Bob's
        assertThat(bob.calls).contains("message:Alex says hi", "actionbar:bar")
                .anySatisfy(call -> assertThat(call).startsWith("title:T|S|"))
                .anySatisfy(call -> assertThat(call).startsWith("sound:entity.player.levelup"));
        assertThat(runner.calls).isEmpty();
    }

    @Test
    void teleportMovesTheTarget() {
        assertThat(run(new TeleportAction(), reader("x", 10, "y", 64, "z", -3), aimedAtBob())).isEqualTo(ActionOutcome.OK);

        assertThat(bob.calls).singleElement().asString().startsWith("teleport:10.0,64.0,-3.0");
        assertThat(runner.calls).isEmpty();
    }

    @Test
    void moneyIsGivenToAndTakenFromTheTarget() {
        var providers = new FakeProviders(new FakePermissions());
        providers.backend.balances.put(runner.uuid, 500d);
        providers.backend.balances.put(bob.uuid, 20d);
        var give = new GiveMoneyAction(providers);
        var take = new TakeMoneyAction(providers);

        assertThat(run(give, reader("amount", 100), aimedAtBob())).isEqualTo(ActionOutcome.OK);
        assertThat(providers.backend.balances).containsEntry(bob.uuid, 120d).containsEntry(runner.uuid, 500d);

        assertThat(run(take, reader("amount", 50), aimedAtBob())).isEqualTo(ActionOutcome.OK);
        assertThat(providers.backend.balances).containsEntry(bob.uuid, 70d).containsEntry(runner.uuid, 500d);

        // the target's own balance decides (insufficient funds is the target's), not the run's player's
        assertThat(run(take, reader("amount", 1000), aimedAtBob())).isInstanceOf(ActionOutcome.Failed.class);
        assertThat(providers.backend.balances).containsEntry(runner.uuid, 500d);
    }

    @Test
    void withNoTargetEveryActionStillActsOnTheRunsPlayer() {
        assertThat(run(new MessageAction(), reader("text", "hi"), runner.context()))
                .isEqualTo(ActionOutcome.OK);
        assertThat(runner.calls).containsExactly("message:hi");
        assertThat(bob.calls).isEmpty();
    }

    @Test
    void aTargetThatIsNotAPlayerFailsAPlayerAction() {
        var zombie = new ResolvedTarget() {
            @Override
            public Object anchor() {
                return "a zombie";
            }

            @Override
            public TargetKind kind() {
                return TargetKind.ENTITY;
            }

            @Override
            public String describe() {
                return "zombie";
            }
        };
        var context = new MapLogicContext(Map.of()).with(Facets.PLAYER, runner.player).with(ResolvedTarget.FACET, zombie);

        assertThat(run(new MessageAction(), reader("text", "hi"), context)).isEqualTo(ActionOutcome.failed("event has no player"));
        assertThat(runner.calls).isEmpty(); // never the run's player instead
    }

    @Test
    void theRetiredMessageChannelsAreRejectedWithTheReplacement() {
        for (String channel : List.of("player", "actionbar", "broadcast")) {
            assertThatThrownBy(() -> new MessageAction().parse(reader("text", "x", "target", channel)))
                    .hasMessageContaining("'" + channel + "' was a message channel");
        }
        assertThat(new MessageAction().parse(reader("text", "x", "target", Map.of("arg", "to"))).text().source()).isEqualTo("x");
    }

    @Test
    void aServerWideSoundTakesNoTarget() {
        assertThatThrownBy(() -> new SoundAction().parse(reader("sound", "x", "scope", "server", "target", "self")))
                .hasMessage("target does not apply to scope server: the sound reaches everyone");
        assertThat(new SoundAction().parse(reader("sound", "x", "scope", "world", "target", "self")).sound().source()).isEqualTo("x");
    }

    @Test
    void theDeclarationsNameTheKindsAndTheSharedParameter() {
        var providers = new FakeProviders(new FakePermissions());
        var targetable = BuiltinLogic.actions(ItemCatalog.none()).stream()
                .filter(action -> action.describe().isTargetable()).toList();

        assertThat(targetable).extracting(ActionType::id).containsExactlyInAnyOrder(
                "message", "actionbar", "title", "sound", "potion", "give_item", "teleport");
        assertThat(new GiveMoneyAction(providers).describe().targetable()).containsExactly(TargetKind.PLAYER);
        assertThat(new TakeMoneyAction(providers).describe().targetable()).containsExactly(TargetKind.PLAYER);
        assertThat(new PotionAction().describe().targetable()).containsExactly(TargetKind.PLAYER, TargetKind.ENTITY);
        assertThat(new TeleportAction().describe().targetable()).containsExactly(TargetKind.PLAYER, TargetKind.ENTITY);
        for (var action : java.util.stream.Stream.concat(targetable.stream(),
                java.util.stream.Stream.of(new GiveMoneyAction(providers), new TakeMoneyAction(providers))).toList()) {
            assertThat(action.describe().self().parameters()).extracting(ParameterSchema::id).endsWith("target");
        }
        // an action the issue does not list stays untargetable and its schema has no target of the shared kind
        assertThat(new BroadcastAction().describe().isTargetable()).isFalse();
        assertThat(new BroadcastAction().describe().self().parameters().stream()
                .filter(parameter -> parameter.id().equals("target")).findFirst().orElseThrow().semantics().valueKind()).isEmpty();
    }
}
