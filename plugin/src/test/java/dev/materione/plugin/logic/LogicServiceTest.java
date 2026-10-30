package dev.materione.plugin.logic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.MaterioneAPI;
import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.DiagnosticsBus;
import dev.materione.api.logic.ActionDescription;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.Availability;
import dev.materione.api.logic.ConditionDescription;
import dev.materione.api.logic.ConditionType;
import dev.materione.api.logic.ExpressionFunction;
import dev.materione.api.logic.FunctionDescription;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.Threading;
import dev.materione.api.logic.TriggerDescription;
import dev.materione.api.logic.TriggerType;
import dev.materione.api.platform.PlatformDescriptor;
import dev.materione.api.registry.BehaviorVocabulary;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.RegistryException;
import dev.materione.api.registry.RegistryKeys;
import dev.materione.api.registry.SelfDescription;
import dev.materione.api.scheduling.SchedulingGateway;
import dev.materione.behavior.logic.MapLogicContext;
import dev.materione.behavior.flow.ActionStep;
import dev.materione.behavior.flow.FlowCompiler;
import dev.materione.core.MaterioneApiImpl;
import dev.materione.core.diagnostics.DiagnosticsBusService;
import dev.materione.core.registry.RegistryService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

/**
 * The logic registries as an addon sees them: only {@code MaterioneAPI.logic()}. A registration is
 * owned by its plugin, reaches the vocabulary and the rule parser, and leaves when it closes or
 * its owner disables.
 */
class LogicServiceTest {

    private final RegistryService registries = new RegistryService();
    private final LogicService service = LogicTestSupport.withBuiltIns(registries);
    private final MaterioneAPI api = new MaterioneApiImpl("test", registries, (DiagnosticsBus) new DiagnosticsBusService(8),
            () -> new PlatformDescriptor("1.21.4", false, false, false, Set.of()), noScheduling(),
            null, null, null, null, null, null, null, null, service);

    @Test
    void anAddonRegistersAnActionAndAConditionThroughTheApiAndBothReachTheVocabulary() {
        Plugin addon = LogicTestSupport.owner("example");
        var action = api.logic().actions().register(addon, NamespacedId.of("example", "greet"), new GreetAction());
        var condition = api.logic().conditions().register(addon, NamespacedId.of("example", "is_night"), new IsNight());

        BehaviorVocabulary vocabulary = api.logic().vocabulary();

        assertThat(vocabulary.actions()).extracting(BehaviorVocabulary.Action::id).contains("example:greet");
        BehaviorVocabulary.Action greet = vocabulary.actions().stream()
                .filter(entry -> entry.id().equals("example:greet")).findFirst().orElseThrow();
        assertThat(greet.registeredBy()).isEqualTo("example");
        assertThat(greet.category()).isEqualTo(LogicCategory.PLAYER);
        assertThat(greet.shorthand()).isEqualTo("text");
        assertThat(greet.description().displayName()).isEqualTo("Greet");
        assertThat(vocabulary.conditions()).extracting(BehaviorVocabulary.Condition::id)
                .containsExactly("example:is_night");
        // the built-ins are there beside them, registered the same way
        assertThat(vocabulary.actions()).extracting(BehaviorVocabulary.Action::id)
                .contains("run_command", "message", "sound", "potion", "cancel", "set_var");
        assertThat(vocabulary.triggers()).hasSize(17);
        assertThat(action.id()).isEqualTo(NamespacedId.of("example", "greet"));
        assertThat(condition.id()).isEqualTo(NamespacedId.of("example", "is_night"));
    }

    @Test
    void anAddonActionIsResolvableByTheRuleParserOnceRegistered() {
        api.logic().actions().register(LogicTestSupport.owner("example"),
                NamespacedId.of("example", "greet"), new GreetAction());
        var parsed = FlowCompiler.compile(NamespacedId.of("sample", "wand"), List.of(Map.of(
                "on", "click", "do", List.of(Map.of("example:greet", "hi")))), service.lookup());

        assertThat(parsed.problems()).isEmpty();
        assertThat(((ActionStep<?>) parsed.rules().getFirst().steps().getFirst()).params()).isEqualTo("hi");
    }

    @Test
    void functionsAndTriggersRegisterToo() {
        Plugin addon = LogicTestSupport.owner("example");
        api.logic().functions().register(addon, NamespacedId.of("example", "shout"), new Shout());
        api.logic().triggers().register(addon, NamespacedId.of("example", "storm"), new Storm());

        BehaviorVocabulary vocabulary = api.logic().vocabulary();

        assertThat(vocabulary.functions()).extracting(BehaviorVocabulary.Function::id).containsExactly("example:shout");
        assertThat(vocabulary.triggers()).extracting(BehaviorVocabulary.Trigger::id).contains("example:storm");
        assertThat(vocabulary.events()).extracting(BehaviorVocabulary.Event::id).contains("example:storm");
    }

    @Test
    void lookupResolvesAuthoredSpellingsAndEntriesCarryTheirOwner() {
        api.logic().actions().register(LogicTestSupport.owner("example"),
                NamespacedId.of("example", "greet"), new GreetAction());

        assertThat(api.logic().actions().resolve("message")).isPresent();
        assertThat(api.logic().actions().resolve("materione:message")).isPresent();
        assertThat(api.logic().actions().resolve("example:greet")).isPresent();
        assertThat(api.logic().actions().resolve("greet")).isEmpty();
        assertThat(api.logic().actions().resolve("not a valid id")).isEmpty();
        assertThat(api.logic().actions().get(NamespacedId.of("example", "greet"))).isPresent();
        assertThat(api.logic().actions().entries()).extracting(entry -> entry.registeredBy())
                .contains("example", "materione");
        // the same registry the introspection view exposes
        assertThat(registries.byKey(RegistryKeys.ACTIONS)).isPresent();
    }

    @Test
    void closingTheHandleOrDisablingTheOwnerRemovesTheType() {
        Plugin addon = LogicTestSupport.owner("example");
        var greet = api.logic().actions().register(addon, NamespacedId.of("example", "greet"), new GreetAction());
        api.logic().conditions().register(addon, NamespacedId.of("example", "is_night"), new IsNight());

        greet.close();
        greet.close(); // idempotent
        assertThat(api.logic().actions().get(NamespacedId.of("example", "greet"))).isEmpty();

        service.ownerDisabled(addon);
        assertThat(api.logic().conditions().entries()).isEmpty();
        assertThat(api.logic().actions().resolve("message")).isPresent(); // other owners are untouched
    }

    @Test
    void registrationRefusesDuplicatesReservedNamespacesMismatchedIdsAndDisabledOwners() {
        Plugin addon = LogicTestSupport.owner("example");
        api.logic().actions().register(addon, NamespacedId.of("example", "greet"), new GreetAction());

        assertThatThrownBy(() -> api.logic().actions().register(
                LogicTestSupport.owner("other"), NamespacedId.of("example", "greet"), new GreetAction()))
                .isInstanceOf(RegistryException.class).hasMessageContaining("duplicate");
        assertThatThrownBy(() -> api.logic().actions().register(
                addon, NamespacedId.of("minecraft", "greet"), new GreetAction()))
                .isInstanceOf(RegistryException.class);
        assertThatThrownBy(() -> api.logic().actions().register(
                addon, NamespacedId.of("example", "other_name"), new GreetAction()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("own id()");
        assertThatThrownBy(() -> api.logic().actions().register(
                LogicTestSupport.disabledOwner("off"), NamespacedId.of("off", "greet"), new GreetAction()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("disabled");
        // an addon cannot take over a built-in's id
        assertThatThrownBy(() -> api.logic().actions().register(
                addon, NamespacedId.of("materione", "message"), new GreetAction() {
                    @Override
                    public String id() {
                        return "message";
                    }
                })).isInstanceOf(RegistryException.class).hasMessageContaining("duplicate");
    }

    @Test
    void aTypeWhoseDescriptionThrowsIsRejectedAndNotRegistered() {
        var broken = new GreetAction() {
            @Override
            public ActionDescription describe() {
                throw new IllegalStateException("describe exploded");
            }
        };
        assertThatThrownBy(() -> api.logic().actions().register(
                LogicTestSupport.owner("example"), NamespacedId.of("example", "greet"), broken))
                .isInstanceOf(IllegalStateException.class);
        assertThat(api.logic().actions().get(NamespacedId.of("example", "greet"))).isEmpty();
    }

    @Test
    void closingTheServiceRemovesEverythingAndRefusesNewRegistrations() {
        service.close();
        service.close();

        assertThat(api.logic().actions().entries()).isEmpty();
        assertThat(api.logic().triggers().entries()).isEmpty();
        assertThatThrownBy(() -> api.logic().actions().register(
                LogicTestSupport.owner("example"), NamespacedId.of("example", "greet"), new GreetAction()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("closed");
    }

    @Test
    void anUnwiredApiSaysSoInsteadOfReturningNull() {
        MaterioneAPI bare = new MaterioneApiImpl("test", registries, new DiagnosticsBusService(8),
                () -> new PlatformDescriptor("1.21.4", false, false, false, Set.of()), noScheduling(),
                null, null, null, null);
        assertThatThrownBy(bare::logic).isInstanceOf(IllegalStateException.class).hasMessageContaining("logic");
    }

    @Test
    void anUnavailableActionStaysListedWithItsReason() {
        api.logic().actions().register(LogicTestSupport.owner("example"),
                NamespacedId.of("example", "greet"), new GreetAction() {
                    @Override
                    public Availability availability() {
                        return Availability.unavailable("Needs the example provider");
                    }
                });
        var greet = api.logic().vocabulary().actions().stream()
                .filter(entry -> entry.id().equals("example:greet")).findFirst().orElseThrow();
        assertThat(greet.availability().available()).isFalse();
        assertThat(greet.availability().reason()).isEqualTo("Needs the example provider");
    }

    private static SchedulingGateway noScheduling() {
        return new SchedulingGateway() {
            @Override
            public void executeSync(Plugin plugin, org.bukkit.Location location, Runnable task) {}

            @Override
            public void executeEntity(Plugin plugin, org.bukkit.entity.Entity entity, Runnable task) {}

            @Override
            public void executeAsync(Plugin plugin, Runnable task) {}

            @Override
            public void executeGlobal(Plugin plugin, Runnable task) {}
        };
    }

    /** An addon action written against the public API only. */
    private static class GreetAction implements ActionType<String> {
        @Override
        public String id() {
            return "greet";
        }

        @Override
        public ActionDescription describe() {
            return new ActionDescription(new SelfDescription("Greet", "Greets the player",
                    List.of(ParameterSchema.of("text", ParameterType.STRING, true)), "1.0.0"),
                    LogicCategory.PLAYER, "text");
        }

        @Override
        public Threading threading() {
            return Threading.PLAYER_REGION;
        }

        @Override
        public String parse(ParamReader params) {
            return params.text("text");
        }

        @Override
        public ActionOutcome execute(String params, LogicContext context) {
            return ActionOutcome.OK;
        }
    }

    /** An addon condition: true while the world's time is night. */
    private static final class IsNight implements ConditionType<Boolean> {
        @Override
        public String id() {
            return "is_night";
        }

        @Override
        public ConditionDescription describe() {
            return ConditionDescription.of(SelfDescription.of("Is night", "True at night"), LogicCategory.WORLD);
        }

        @Override
        public Boolean parse(ParamReader params) {
            return params.bool("invert", false);
        }

        @Override
        public boolean test(Boolean invert, LogicContext context) {
            Object time = context.value("world.time");
            boolean night = time instanceof Number number && number.longValue() >= 13_000;
            return night != invert;
        }
    }

    /** An addon function. */
    private static final class Shout implements ExpressionFunction {
        @Override
        public String id() {
            return "shout";
        }

        @Override
        public FunctionDescription describe() {
            return FunctionDescription.of(new SelfDescription("Shout", "Upper-cases text",
                    List.of(ParameterSchema.of("text", ParameterType.STRING, true)), "1.0.0"), ParameterType.STRING);
        }

        @Override
        public Object call(List<Object> arguments, LogicContext context) {
            return String.valueOf(arguments.getFirst()).toUpperCase(java.util.Locale.ROOT);
        }
    }

    /** An addon trigger: a description only. */
    private static final class Storm implements TriggerType {
        @Override
        public String id() {
            return "storm";
        }

        @Override
        public TriggerDescription describe() {
            return new TriggerDescription(SelfDescription.of("Storm", "A storm rolls in"),
                    List.of(), List.of("example"), List.of(), List.of(), false);
        }
    }

    @Test
    void anAddonConditionRunsAgainstAFakeContext() {
        var night = new IsNight();
        var parsed = night.parse(ParamReader.of(Map.of()));

        assertThat(night.test(parsed, new MapLogicContext(Map.of("world", Map.of("time", 14_000))))).isTrue();
        assertThat(night.test(parsed, new MapLogicContext(Map.of("world", Map.of("time", 1_000))))).isFalse();
        assertThat(night.test(night.parse(ParamReader.of(Map.of("invert", true))),
                new MapLogicContext(Map.of("world", Map.of("time", 1_000))))).isTrue();
    }
}
