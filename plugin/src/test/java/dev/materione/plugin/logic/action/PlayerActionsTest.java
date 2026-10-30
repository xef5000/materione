package dev.materione.plugin.logic.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ParamReader;
import dev.materione.behavior.logic.EngineExpressions;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The player-facing actions added by #212, each against a {@link PlayerFake}: parse accept and reject,
 * and execute on the paths that need no live server. The paths that call static server state
 * ({@code Bukkit.broadcast}, {@code Bukkit.getWorld}, {@code Bukkit.getOnlinePlayers}) are proven by
 * the in-game QA suite ({@code qa/catalog/logic.mjs}), because a unit test has no server to ask.
 */
class PlayerActionsTest {

    static ParamReader reader(Map<String, Object> params) {
        return ParamReader.of(params, EngineExpressions.INSTANCE);
    }

    static Map<String, Object> params(Object... keyValues) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    @Nested
    class Actionbar {
        private final ActionbarAction action = new ActionbarAction();

        @Test
        void sendsTheRenderedTextToTheActionBarAndNeedsAPlayer() {
            var fake = new PlayerFake();
            var parsed = action.parse(reader(params("text", "&a${player.name} on %server%")));

            assertThat(action.execute(parsed, fake.context()).isOk()).isTrue();
            assertThat(fake.calls).containsExactly("actionbar:Alex on Lobby");
            assertThat(action.execute(parsed, PlayerFake.noPlayer()))
                    .isEqualTo(ActionOutcome.failed("event has no player"));
        }

        @Test
        void rejectsAMissingText() {
            assertThatThrownBy(() -> action.parse(reader(params()))).hasMessage("text is required");
        }
    }

    @Nested
    class Title {
        private final TitleAction action = new TitleAction();

        @Test
        void parsesTheTimingsWithVanillaDefaults() {
            var parsed = action.parse(reader(params("title", "Hi")));
            assertThat(parsed.subtitle()).isEmpty();
            assertThat(parsed.fadeIn()).isEqualTo(10);
            assertThat(parsed.stay()).isEqualTo(70);
            assertThat(parsed.fadeOut()).isEqualTo(20);
        }

        @Test
        void showsTitleAndSubtitleWithTheAuthoredTicks() {
            var fake = new PlayerFake();
            var parsed = action.parse(reader(params("title", "&6Welcome ${player.name}", "subtitle", "to %server%",
                    "fade_in", 5, "stay", 40, "fade_out", 10)));

            assertThat(action.execute(parsed, fake.context()).isOk()).isTrue();
            assertThat(fake.calls).containsExactly("title:Welcome Alex|to Lobby|5,40,10");
        }

        @Test
        void rejectsAMissingTitleAndBadTimings() {
            assertThatThrownBy(() -> action.parse(reader(params("subtitle", "x"))))
                    .hasMessage("title is required");
            assertThatThrownBy(() -> action.parse(reader(params("title", "x", "stay", -1))))
                    .hasMessageStartingWith("stay must be an integer");
            assertThatThrownBy(() -> action.parse(reader(params("title", "x", "fade_in", "5"))))
                    .hasMessageStartingWith("fade_in must be an integer");
        }
    }

    @Nested
    class Broadcast {
        private final BroadcastAction action = new BroadcastAction();

        @Test
        void parsesEveryScopeAndDemandsAPermissionNodeForPermissionScope() {
            assertThat(action.parse(reader(params("text", "x"))).scope()).isEqualTo(BroadcastAction.Scope.SERVER);
            assertThat(action.parse(reader(params("text", "x", "scope", "World"))).scope())
                    .isEqualTo(BroadcastAction.Scope.WORLD);
            assertThat(action.parse(reader(params("text", "x", "scope", "permission", "target", "a.b"))).target())
                    .isPresent();

            assertThatThrownBy(() -> action.parse(reader(params("text", "x", "scope", "permission"))))
                    .hasMessage("target is required for scope permission");
            assertThatThrownBy(() -> action.parse(reader(params("text", "x", "scope", "everyone"))))
                    .hasMessage("scope must be one of server, world, permission");
        }

        @Test
        void worldScopeReachesEveryPlayerInTheEventPlayersWorld() {
            var fake = new PlayerFake();
            var neighbour = new PlayerFake();
            fake.worldPlayers.add(fake.player);
            fake.worldPlayers.add(neighbour.player);

            var parsed = action.parse(reader(params("text", "Boss down", "scope", "world")));
            assertThat(action.execute(parsed, fake.context()).isOk()).isTrue();
            assertThat(fake.calls).containsExactly("message:Boss down");
            assertThat(neighbour.calls).containsExactly("message:Boss down");
        }

        @Test
        void aBlankPermissionNodeIsRejectedWhenTheStepLoads() {
            assertThatThrownBy(() -> action.parse(reader(params("text", "x", "scope", "permission", "target", "  "))))
                    .hasMessage("target must be a non-blank string");
        }

        @Test
        void aWorldScopeWithoutAPlayerOrTargetFails() {
            var parsed = action.parse(reader(params("text", "x", "scope", "world")));
            assertThat(action.execute(parsed, PlayerFake.noPlayer()))
                    .isEqualTo(ActionOutcome.failed("event has no player"));
        }
    }

    @Nested
    class StopSound {
        private final StopSoundAction action = new StopSoundAction();

        @Test
        void parsesTheOptionalSoundAndCategoryAndRejectsAnUnknownCategory() {
            var none = action.parse(reader(params()));
            assertThat(none.sound()).isEmpty();
            assertThat(none.category()).isNull();
            assertThat(action.parse(reader(params("sound", "minecraft:music.game", "category", "music")))
                    .category()).isEqualTo(org.bukkit.SoundCategory.MUSIC);

            assertThatThrownBy(() -> action.parse(reader(params("category", "loud"))))
                    .hasMessageStartingWith("category must be one of");
        }

        @Test
        void stopsTheRightThingForEachCombination() {
            var fake = new PlayerFake();
            action.execute(action.parse(reader(params("sound", "minecraft:music.game"))), fake.context());
            action.execute(action.parse(reader(params("category", "music"))), fake.context());
            action.execute(action.parse(reader(params("sound", "minecraft:music.game", "category", "music"))),
                    fake.context());
            action.execute(action.parse(reader(params())), fake.context());

            assertThat(fake.calls).containsExactly(
                    "stop:minecraft:music.game",
                    "stop:MUSIC",
                    "stop:minecraft:music.game:MUSIC",
                    "stop:all");
        }
    }

    @Nested
    class Chat {
        private final ChatAction action = new ChatAction();

        @Test
        void aPlainLineIsSaidAsThePlayerAndASlashLineRunsAsACommand() {
            var fake = new PlayerFake();
            assertThat(action.execute(action.parse(reader(params("text", "Hi ${player.name}"))), fake.context())
                    .isOk()).isTrue();
            assertThat(action.execute(action.parse(reader(params("text", "/spawn"))), fake.context()).isOk())
                    .isTrue();
            assertThat(fake.calls).containsExactly("chat:Hi Alex", "command:spawn");
        }

        @Test
        void aMissingCommandFailsAndABlankLineIsRejectedWhenTheStepLoads() {
            var fake = new PlayerFake();
            fake.commandExists = false;
            assertThat(action.execute(action.parse(reader(params("text", "/nope"))), fake.context()))
                    .isEqualTo(ActionOutcome.failed("player has no command 'nope'"));
            assertThatThrownBy(() -> action.parse(reader(params("text", "   "))))
                    .hasMessage("text must be a non-blank string");
            assertThat(action.execute(action.parse(reader(params("text", "x"))), PlayerFake.noPlayer()))
                    .isEqualTo(ActionOutcome.failed("event has no player"));
        }
    }

    @Nested
    class Teleport {
        private final TeleportAction action = new TeleportAction();

        @Test
        void parsesLiteralAndTemplatedCoordinatesAndRejectsMissingOrOutOfRangeOnes() {
            var parsed = action.parse(reader(params("x", 1.5, "y", "${vars.y}", "z", "%z%")));
            assertThat(parsed.x().literal()).isEqualTo(1.5);
            assertThat(parsed.yaw()).isEmpty();

            assertThatThrownBy(() -> action.parse(reader(params("y", 1, "z", 2)))).hasMessage("x is required");
            assertThatThrownBy(() -> action.parse(reader(params("x", "5", "y", 1, "z", 2))))
                    .hasMessage("x must be a number or a template that renders to one");
            assertThatThrownBy(() -> action.parse(reader(params("x", 1, "y", 1, "z", 2, "pitch", 120))))
                    .hasMessage("pitch must be a number in -90.0..90.0");
        }

        @Test
        void movesThePlayerToTheRenderedCoordinatesKeepingTheirFacingByDefault() {
            var fake = new PlayerFake();
            var parsed = action.parse(reader(params("x", 10, "y", "${vars.y}", "z", -4.5)));
            var context = fake.context();
            context.locals().put("y", 64);

            assertThat(action.execute(parsed, context).isOk()).isTrue();
            assertThat(fake.calls).containsExactly("teleport:10.0,64.0,-4.5 yaw=90.0 pitch=10.0");
        }

        @Test
        void aRefusedTeleportFailsAndANonNumberFailsWithoutMovingThePlayer() {
            var fake = new PlayerFake();
            fake.teleportAccepted = false;
            assertThat(action.execute(action.parse(reader(params("x", 1, "y", 1, "z", 1))), fake.context()))
                    .isEqualTo(ActionOutcome.failed("the server refused the teleport"));

            var bad = action.parse(reader(params("x", "${vars.nope}", "y", 1, "z", 1)));
            assertThat(action.execute(bad, fake.context()))
                    .isEqualTo(ActionOutcome.failed("teleport coordinates must be numbers"));
            assertThat(fake.calls).hasSize(1);
        }
    }

    @Nested
    class Sound {
        private final SoundAction action = new SoundAction();

        @Test
        void aWorldScopePlaysToEveryoneInTheWorldAndDefaultsToThePlayerScope() {
            var fake = new PlayerFake();
            var neighbour = new PlayerFake();
            fake.worldPlayers.add(fake.player);
            fake.worldPlayers.add(neighbour.player);

            assertThat(action.parse(reader(params("sound", "x"))).scope()).isEqualTo(SoundAction.Scope.PLAYER);
            var world = action.parse(reader(params("sound", "entity.pig.ambient", "scope", "world")));
            assertThat(action.execute(world, fake.context()).isOk()).isTrue();
            assertThat(fake.calls).containsExactly("sound:entity.pig.ambient:1.0:1.0");
            assertThat(neighbour.calls).containsExactly("sound:entity.pig.ambient:1.0:1.0");
        }

        @Test
        void rejectsAnUnknownScopeAndDeclaresItsThreadingAsGlobal() {
            assertThatThrownBy(() -> action.parse(reader(params("sound", "x", "scope", "room"))))
                    .hasMessage("scope must be one of player, world, server");
            assertThat(action.threading()).isEqualTo(dev.materione.api.logic.Threading.GLOBAL);
        }
    }

    @Nested
    class Experience {
        @Test
        void giveAddsPointsOrLevelsAndTakeRemovesOnlyWhenTheAmountIsThere() {
            var fake = new PlayerFake();
            fake.totalExperience = 30;
            fake.level = 2;
            var give = new GiveExpAction();
            var take = new TakeExpAction();

            assertThat(give.execute(give.parse(reader(params("amount", 20))), fake.context()).isOk()).isTrue();
            assertThat(give.execute(give.parse(reader(params("amount", 3, "unit", "levels"))), fake.context())
                    .isOk()).isTrue();
            assertThat(fake.totalExperience).isEqualTo(50);
            assertThat(fake.level).isEqualTo(5);

            assertThat(take.execute(take.parse(reader(params("amount", 60))), fake.context()))
                    .isEqualTo(ActionOutcome.failed("player has 50 experience points, needs 60"));
            assertThat(fake.totalExperience).isEqualTo(50);
            assertThat(take.execute(take.parse(reader(params("amount", 10))), fake.context()).isOk()).isTrue();
            assertThat(fake.totalExperience).isEqualTo(40);

            assertThat(take.execute(take.parse(reader(params("amount", 9, "unit", "levels"))), fake.context()))
                    .isEqualTo(ActionOutcome.failed("player has level 5, needs 9"));
            assertThat(fake.level).isEqualTo(5);
            assertThat(take.execute(take.parse(reader(params("amount", 5, "unit", "levels"))), fake.context()).isOk())
                    .isTrue();
            assertThat(fake.level).isEqualTo(0);
        }

        @Test
        void aMissingPlayerOrAnUnparsableAmountFails() {
            assertThatThrownBy(() -> new GiveExpAction().parse(reader(params()))).hasMessage("amount is required");
            assertThatThrownBy(() -> new TakeExpAction().parse(reader(params("amount", 1.5))))
                    .hasMessageStartingWith("amount must be");
            var give = new GiveExpAction();
            assertThat(give.execute(give.parse(reader(params("amount", 1))), PlayerFake.noPlayer()))
                    .isEqualTo(ActionOutcome.failed("event has no player"));
        }
    }
}
