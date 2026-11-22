package dev.materione.plugin.logic.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.logic.ConditionType;
import dev.materione.api.logic.Facets;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.protection.ProtectionMode;
import dev.materione.behavior.logic.EngineExpressions;
import dev.materione.behavior.logic.MapLogicContext;
import dev.materione.plugin.logic.BuiltinLogic;
import dev.materione.plugin.logic.action.WorldFakes.FakePort;
import dev.materione.plugin.logic.action.WorldFakes.Living;
import dev.materione.plugin.logic.action.WorldFakes.Platform;
import dev.materione.plugin.logic.condition.CanBreakCondition;
import dev.materione.plugin.logic.condition.CanBuildCondition;
import dev.materione.plugin.logic.condition.CanInteractCondition;
import dev.materione.plugin.protection.ProtectionSettings;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code can_build}, {@code can_break} and {@code can_interact} (logic.md §43, protection.md section 7): content asking the port
 * itself. True unless a provider denies, whatever the mode says, with the location and the actor chosen like a verb's.
 */
class ProtectionConditionsTest {

    private static ParamReader reader(Object... keyValues) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return ParamReader.of(map, EngineExpressions.INSTANCE);
    }

    private static <P> boolean test(ConditionType<P> condition, ParamReader params, LogicContext context) {
        return condition.test(condition.parse(params), context);
    }

    private final Living runner = new Living();

    private Platform platform(ProtectionSettings settings, FakePort port) {
        var platform = new Platform(settings);
        platform.protection.register(WorldFakes.enabledPlugin(), port);
        runner.location.setWorld(platform.terrain.world);
        return platform;
    }

    private MapLogicContext run() {
        return new MapLogicContext(Map.of("location", Map.of("x", 10, "y", 64, "z", 10, "world", "world")))
                .with(Facets.PLAYER, runner.player);
    }

    private MapLogicContext playerless() {
        return new MapLogicContext(Map.of("location", Map.of("x", 10, "y", 64, "z", 10, "world", "world")));
    }

    @Test
    void canBuildIsTrueUnlessAProviderDeniesPlacingAtTheLocation() {
        var port = new FakePort();
        port.denied.add("PLACE@10,64,10");
        var platform = platform(new ProtectionSettings(ProtectionMode.BOTH, true), port);
        var condition = new CanBuildCondition(platform.verbs);

        assertThat(test(condition, reader("at", "event"), run())).isFalse();
        assertThat(test(condition, reader("at", Map.of("of", "event", "x", 1)), run())).isTrue();
        assertThat(port.asked).containsExactly("PLACE@10,64,10 by player", "PLACE@11,64,10 by player");
    }

    @Test
    void canBreakAndCanInteractAskTheirOwnQuestion() {
        var port = new FakePort();
        port.denied.add("BREAK@10,64,10");
        var platform = platform(new ProtectionSettings(ProtectionMode.BOTH, true), port);

        assertThat(test(new CanBreakCondition(platform.verbs), reader("at", "event"), run())).isFalse();
        assertThat(test(new CanInteractCondition(platform.verbs), reader("at", "event"), run())).isTrue();
        assertThat(port.asked).containsExactly("BREAK@10,64,10 by player", "INTERACT@10,64,10 by player");
    }

    @Test
    void theDefaultLocationIsWhereThePlayerStands() {
        var port = new FakePort();
        var platform = platform(new ProtectionSettings(ProtectionMode.BOTH, true), port);

        assertThat(test(new CanBuildCondition(platform.verbs), reader(), run())).isTrue();

        assertThat(port.asked).containsExactly("PLACE@0,64,0 by player");
    }

    @Test
    void itAsksWhateverTheModeSaysBecauseItIsTheAuthorAsking() {
        var port = new FakePort();
        port.denied.add("PLACE@10,64,10");
        var platform = platform(ProtectionSettings.EVENT_ONLY, port);

        assertThat(test(new CanBuildCondition(platform.verbs), reader("at", "event"), run())).isFalse();
    }

    @Test
    void asPlayerFalseAsksWithNoActorAndWorksWithoutAPlayer() {
        var port = new FakePort();
        port.denied.add("PLACE@10,64,10");
        var platform = platform(new ProtectionSettings(ProtectionMode.BOTH, true), port);
        var condition = new CanBuildCondition(platform.verbs);

        assertThat(test(condition, reader("at", "event", "as_player", false), playerless())).isFalse();
        assertThat(port.asked).containsExactly("PLACE@10,64,10 by nobody");
        // a run with no player cannot ask as one
        assertThat(test(condition, reader("at", "event"), playerless())).isFalse();
    }

    @Test
    void withNoProviderNothingProtectsThePlace() {
        var platform = new Platform(new ProtectionSettings(ProtectionMode.BOTH, true));
        runner.location.setWorld(platform.terrain.world);

        assertThat(test(new CanBuildCondition(platform.verbs), reader("at", "event"), run())).isTrue();
    }

    @Test
    void aLocationThatCannotBeReadIsFalse() {
        var port = new FakePort();
        var platform = platform(new ProtectionSettings(ProtectionMode.BOTH, true), port);

        assertThat(test(new CanBuildCondition(platform.verbs), reader("at", "event"),
                new MapLogicContext(Map.of()).with(Facets.PLAYER, runner.player))).isFalse();
        assertThat(port.asked).isEmpty();
    }

    @Test
    void theThreeAreRegisteredWithFullSelfDescriptionAndAnEventNeedsALocation() {
        var platform = platform(new ProtectionSettings(ProtectionMode.BOTH, true), new FakePort());

        List<ConditionType<?>> conditions = BuiltinLogic.protectionConditions(platform.verbs);

        assertThat(conditions).extracting(ConditionType::id).containsExactly("can_build", "can_break", "can_interact");
        for (ConditionType<?> condition : conditions) {
            var description = condition.describe();
            assertThat(description.shorthand()).isEqualTo("at");
            assertThat(description.self().description()).isNotBlank();
            assertThat(description.self().parameters()).extracting(p -> p.id()).containsExactly("at", "as_player");
            assertThat(description.self().parameters()).allSatisfy(p -> assertThat(p.description()).isNotBlank());
        }
        var condition = new CanBuildCondition(platform.verbs);
        assertThat(condition.parse(reader("at", "player")).asPlayer()).isTrue();
        assertThatThrownBy(() -> condition.parse(reader("at", "target"))).hasMessageContaining("no target");
    }
}
