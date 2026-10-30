package dev.materione.plugin.logic.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.Cancellation;
import dev.materione.api.logic.Facets;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.Threading;
import dev.materione.behavior.logic.EngineExpressions;
import dev.materione.behavior.logic.MapLogicContext;
import dev.materione.plugin.logic.BuiltinLogic;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The six M2 actions as typed {@code ActionType}s: each rejects bad parameters when it parses, and
 * runs against a fake context whose player is a recording stand-in (no server). The paths that need
 * live server state (the console dispatcher, the potion effect registry) are covered by the
 * mechanics QA suite and the headless-Paper harness.
 */
class BuiltinActionsTest {

    private static ParamReader reader(Map<String, Object> params) {
        return ParamReader.of(params, EngineExpressions.INSTANCE);
    }

    private static Map<String, Object> params(Object... keyValues) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    /** A context whose player records every call made to it. */
    private static final class Recorded {
        final List<String> calls = new ArrayList<>();
        final boolean[] op = {false};
        final Player player = proxy(Player.class, (target, method, args) -> switch (method.getName()) {
            case "sendMessage" -> record("message", args[0]);
            case "sendActionBar" -> record("actionbar", args[0]);
            case "playSound" -> {
                calls.add("sound:" + args[1] + ":" + args[2] + ":" + args[3]);
                yield null;
            }
            case "getLocation" -> null;
            case "performCommand" -> {
                calls.add("command:" + args[0] + ":op=" + op[0]);
                yield !String.valueOf(args[0]).startsWith("nope");
            }
            case "isOp" -> op[0];
            case "setOp" -> {
                op[0] = (Boolean) args[0];
                yield null;
            }
            default -> throw new AssertionError("unexpected player call: " + method.getName());
        });

        private Object record(String kind, Object component) {
            calls.add(kind + ":" + (component instanceof Component text
                    ? PlainTextComponentSerializer.plainText().serialize(text) : component));
            return null;
        }

        MapLogicContext context() {
            return new MapLogicContext(Map.of("player", Map.of("name", "Alex"), "item", Map.of("amount", 3d)))
                    .with(Facets.PLAYER, player)
                    .withPlaceholders(text -> text.replace("%server%", "Lobby"));
        }
    }

    @Test
    void theSixBuiltInsDescribeThemselvesWithCategoryThreadingAndShorthand() {
        var actions = BuiltinLogic.actions();

        assertThat(actions).extracting(action -> action.id())
                .containsExactly("run_command", "message", "sound", "potion", "cancel", "set_var");
        assertThat(actions).extracting(action -> action.describe().category()).containsExactly(
                LogicCategory.SERVER, LogicCategory.PLAYER, LogicCategory.PLAYER, LogicCategory.PLAYER,
                LogicCategory.FLOW, LogicCategory.DATA);
        assertThat(actions).extracting(action -> action.threading()).containsExactly(
                Threading.GLOBAL, Threading.PLAYER_REGION, Threading.PLAYER_REGION, Threading.PLAYER_REGION,
                Threading.ANY, Threading.ANY);
        assertThat(actions).extracting(action -> action.describe().shorthand())
                .containsExactly("command", "text", "sound", null, "value", null);
        assertThat(actions).allSatisfy(action -> {
            assertThat(action.describe().self().parameters()).isNotEmpty();
            assertThat(action.availability().available()).isTrue();
        });
    }

    @Nested
    class RunCommand {
        private final RunCommandAction action = new RunCommandAction();

        @Test
        void theThreadFollowsTheSenderTheConsoleIsGlobalAndPlayerAndOpRunInThePlayersRegion() {
            assertThat(action.threading()).isEqualTo(Threading.GLOBAL); // the vocabulary's headline value
            assertThat(action.threading(action.parse(reader(params("command", "say hi")))))
                    .isEqualTo(Threading.GLOBAL);
            assertThat(action.threading(action.parse(reader(params("command", "spawn", "as", "console")))))
                    .isEqualTo(Threading.GLOBAL);
            assertThat(action.threading(action.parse(reader(params("command", "spawn", "as", "player")))))
                    .isEqualTo(Threading.PLAYER_REGION);
            assertThat(action.threading(action.parse(reader(params("command", "spawn", "as", "op")))))
                    .isEqualTo(Threading.PLAYER_REGION);
        }

        @Test
        void parsesTheScalarFormAndEveryDocumentedSender() {
            var plain = action.parse(reader(params("command", "say hi")));
            assertThat(plain.command().source()).isEqualTo("say hi");
            assertThat(plain.sender()).isEqualTo(RunCommandAction.Sender.CONSOLE);
            assertThat(action.parse(reader(params("command", "x", "as", "Player"))).sender())
                    .isEqualTo(RunCommandAction.Sender.PLAYER);
            assertThat(action.parse(reader(params("command", "x", "as", "op"))).sender())
                    .isEqualTo(RunCommandAction.Sender.OP);
        }

        @Test
        void rejectsAMissingCommandAnUnknownSenderAndABrokenTemplate() {
            assertThatThrownBy(() -> action.parse(reader(params()))).hasMessage("command is required");
            assertThatThrownBy(() -> action.parse(reader(params("command", 4)))).hasMessageContaining("command");
            assertThatThrownBy(() -> action.parse(reader(params("command", "x", "as", "root"))))
                    .hasMessage("as must be one of console, player, op");
            assertThatThrownBy(() -> action.parse(reader(params("command", "say ${}"))))
                    .hasMessageStartingWith("command:");
        }

        @Test
        void aPlayerCommandRunsRenderedWithoutItsSlashAndAnUnknownOneFails() {
            var recorded = new Recorded();
            var parsed = action.parse(reader(params("command", "/say ${player.name} %server%", "as", "player")));

            assertThat(action.execute(parsed, recorded.context())).isEqualTo(ActionOutcome.OK);
            assertThat(recorded.calls).containsExactly("command:say Alex Lobby:op=false");

            var missing = action.parse(reader(params("command", "nope now", "as", "player")));
            assertThat(action.execute(missing, recorded.context()))
                    .isEqualTo(ActionOutcome.failed("player has no command 'nope now'"));
        }

        @Test
        void anOpCommandRunsWithTemporaryOperatorRightsThatAreAlwaysRestored() {
            var recorded = new Recorded();
            var parsed = action.parse(reader(params("command", "gamemode creative", "as", "op")));

            assertThat(action.execute(parsed, recorded.context()).isOk()).isTrue();
            assertThat(recorded.calls).containsExactly("command:gamemode creative:op=true");
            assertThat(recorded.op[0]).isFalse();
        }

        @Test
        void aPlayerSenderWithoutAPlayerFailsInsteadOfThrowing() {
            var parsed = action.parse(reader(params("command", "x", "as", "player")));
            assertThat(action.execute(parsed, new MapLogicContext(Map.of())))
                    .isEqualTo(ActionOutcome.failed("event has no player"));
        }
    }

    @Nested
    class Message {
        private final MessageAction action = new MessageAction();

        @Test
        void parsesTextAndTargetAndRejectsBadOnes() {
            var parsed = action.parse(reader(params("text", "&aHi", "target", "ActionBar")));
            assertThat(parsed.text().source()).isEqualTo("&aHi");
            assertThat(parsed.target()).isEqualTo(MessageAction.Target.ACTIONBAR);
            assertThat(action.parse(reader(params("text", "x"))).target()).isEqualTo(MessageAction.Target.PLAYER);

            assertThatThrownBy(() -> action.parse(reader(params()))).hasMessage("text is required");
            assertThatThrownBy(() -> action.parse(reader(params("text", "x", "target", "everyone"))))
                    .hasMessage("target must be one of player, actionbar, broadcast");
        }

        @Test
        void sendsTheRenderedTextToThePlayerOrTheActionBar() {
            var recorded = new Recorded();
            action.execute(action.parse(reader(params("text", "Hi ${player.name} x${item.amount} on %server%"))),
                    recorded.context());
            action.execute(action.parse(reader(params("text", "bar", "target", "actionbar"))), recorded.context());

            assertThat(recorded.calls).containsExactly("message:Hi Alex x3 on Lobby", "actionbar:bar");
        }

        @Test
        void aMissingPlayerFails() {
            assertThat(action.execute(action.parse(reader(params("text", "x"))), new MapLogicContext(Map.of())))
                    .isEqualTo(ActionOutcome.failed("event has no player"));
        }
    }

    @Nested
    class Sound {
        private final SoundAction action = new SoundAction();

        @Test
        void parsesTheSoundWithVolumeAndPitchDefaultsAndRejectsOutOfRangeValues() {
            var parsed = action.parse(reader(params("sound", "minecraft:block.note_block.pling")));
            assertThat(parsed.volume()).isEqualTo(1f);
            assertThat(parsed.pitch()).isEqualTo(1f);
            assertThat(action.parse(reader(params("sound", "x", "volume", 0.5, "pitch", 2))).pitch()).isEqualTo(2f);

            assertThatThrownBy(() -> action.parse(reader(params()))).hasMessage("sound is required");
            assertThatThrownBy(() -> action.parse(reader(params("sound", "x", "volume", "loud"))))
                    .hasMessageStartingWith("volume must be");
            assertThatThrownBy(() -> action.parse(reader(params("sound", "x", "pitch", -1))))
                    .hasMessageStartingWith("pitch must be");
        }

        @Test
        void playsTheRenderedKeyAtThePlayer() {
            var recorded = new Recorded();
            var parsed = action.parse(reader(params("sound", "entity.${item.amount}", "volume", 0.5, "pitch", 2)));

            assertThat(action.execute(parsed, recorded.context()).isOk()).isTrue();
            assertThat(recorded.calls).containsExactly("sound:entity.3:0.5:2.0");
            assertThat(action.execute(parsed, new MapLogicContext(Map.of())).isOk()).isFalse();
        }
    }

    @Nested
    class Potion {
        private final PotionAction action = new PotionAction();

        @Test
        void parsesTheEffectWithItsDefaultsAndRejectsBadValues() {
            var parsed = action.parse(reader(params("type", "Speed")));
            assertThat(parsed.effect().getKey()).isEqualTo("speed");
            assertThat(parsed.duration()).isEqualTo(200);
            assertThat(parsed.amplifier()).isEqualTo(0);
            assertThat(action.parse(reader(params("type", "speed", "duration", 40, "amplifier", 2))).amplifier())
                    .isEqualTo(2);

            assertThatThrownBy(() -> action.parse(reader(params()))).hasMessage("type is required");
            assertThatThrownBy(() -> action.parse(reader(params("type", "not an effect"))))
                    .hasMessageContaining("not a valid effect name");
            assertThatThrownBy(() -> action.parse(reader(params("type", "speed", "duration", 0))))
                    .hasMessageStartingWith("duration must be an integer");
            assertThatThrownBy(() -> action.parse(reader(params("type", "speed", "amplifier", 300))))
                    .hasMessageStartingWith("amplifier must be an integer");
        }

        @Test
        void aMissingPlayerFailsBeforeTheServerIsAsked() {
            assertThat(action.execute(action.parse(reader(params("type", "speed"))), new MapLogicContext(Map.of())))
                    .isEqualTo(ActionOutcome.failed("event has no player"));
        }
    }

    @Nested
    class Cancel {
        private final CancelAction action = new CancelAction();

        private static final class Flag implements Cancellation {
            boolean requested;

            @Override
            public void request() {
                requested = true;
            }

            @Override
            public boolean requested() {
                return requested;
            }
        }

        @Test
        void trueRequestsCancellationAndFalseDoesNothing() {
            var flag = new Flag();
            var context = new MapLogicContext(Map.of()).with(Cancellation.FACET, flag);

            assertThat(action.execute(action.parse(reader(params("value", false))), context).isOk()).isTrue();
            assertThat(flag.requested).isFalse();
            assertThat(action.execute(action.parse(reader(params())), context).isOk()).isTrue(); // default true
            assertThat(flag.requested).isTrue();
        }

        @Test
        void rejectsANonBooleanAndFailsWhenTheEventCannotBeCancelled() {
            assertThatThrownBy(() -> action.parse(reader(params("value", "yes")))).hasMessage("value must be true or false");
            assertThat(action.execute(action.parse(reader(params())), new MapLogicContext(Map.of())))
                    .isEqualTo(ActionOutcome.failed("this event cannot be cancelled"));
        }
    }

    @Nested
    class SetVar {
        private final SetVarAction action = new SetVarAction();

        @Test
        void aLiteralIsStoredAsWritten() {
            var context = new MapLogicContext(Map.of());
            action.execute(action.parse(reader(params("name", "count", "value", 5))), context);
            action.execute(action.parse(reader(params("name", "label", "value", "gold"))), context);

            assertThat(context.locals()).containsEntry("count", 5).containsEntry("label", "gold");
            assertThat(context.value("vars.count")).isEqualTo(5);
        }

        @Test
        void aTemplateRendersToTextEachRun() {
            var context = new MapLogicContext(Map.of("item", Map.of("amount", 3d)));
            var parsed = action.parse(reader(params("name", "msg", "value", "have ${item.amount} on %server%")));

            assertThat(parsed.value()).isInstanceOf(SetVarAction.Templated.class);
            action.execute(parsed, context.withPlaceholders(text -> text.replace("%server%", "Lobby")));
            assertThat(context.locals()).containsEntry("msg", "have 3 on Lobby");
        }

        @Test
        void anExpressionIsEvaluatedToATypedValue() {
            var context = new MapLogicContext(Map.of("item", Map.of("amount", 3d)));
            var parsed = action.parse(reader(params("name", "same", "value", "item.amount == 3")));

            assertThat(parsed.value()).isInstanceOf(SetVarAction.Computed.class);
            action.execute(parsed, context);
            assertThat(context.locals()).containsEntry("same", true);
        }

        @Test
        void rejectsAMissingNameAndAnExpressionThatDoesNotParse() {
            assertThatThrownBy(() -> action.parse(reader(params("value", 1)))).hasMessage("name is required");
            assertThatThrownBy(() -> action.parse(reader(params("name", "x", "value", "1 +"))))
                    .hasMessageStartingWith("value:");
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, RecordingHandler handler) {
        InvocationHandler invocation = (target, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> target == args[0];
                    case "hashCode" -> System.identityHashCode(target);
                    default -> type.getSimpleName() + " stub";
                };
            }
            return handler.handle(target, method, args);
        };
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, invocation);
    }

    @FunctionalInterface
    private interface RecordingHandler {
        Object handle(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable;
    }
}
