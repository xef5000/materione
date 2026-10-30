package dev.materione.behavior.logic;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
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
import dev.materione.api.registry.BehaviorVocabulary;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.RegistryKeys;
import dev.materione.api.registry.SelfDescription;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The version-2 vocabulary is read from the registries, never from a second list. */
class VocabularyBuilderTest {

    private final LogicFixture logic = new LogicFixture();

    private BehaviorVocabulary build() {
        return VocabularyBuilder.build(logic.registries());
    }

    @Test
    void theBuiltInTriggersAreTheSeventeenEventsOfTheTaxonomyInTheirEditorOrder() {
        BehaviorVocabulary vocabulary = build();

        assertThat(vocabulary.events()).extracting(BehaviorVocabulary.Event::id).containsExactly(
                "click", "break", "place", "interact", "equip", "unequip", "drop", "pickup", "consume",
                "damage", "death", "inv_click", "tick", "menu.click", "hud.threshold", "dialog.submit", "custom");
        assertThat(vocabulary.triggers()).extracting(BehaviorVocabulary.Trigger::id)
                .isEqualTo(vocabulary.events().stream().map(BehaviorVocabulary.Event::id).toList());

        BehaviorVocabulary.Event click = vocabulary.events().get(0);
        assertThat(click.details()).containsExactly("right", "left");
        assertThat(click.firedBy()).containsExactly("item", "furniture");
        // reserved events stay listed but nothing fires them
        assertThat(vocabulary.events().get(12).firedBy()).isEmpty();
    }

    @Test
    void triggersCarryTheirContextSchemaAndCancellability() {
        BehaviorVocabulary.Trigger click = build().triggers().get(0);
        BehaviorVocabulary.Trigger place = build().triggers().get(2);

        assertThat(click.description().cancellable()).isTrue();
        assertThat(place.description().cancellable()).isFalse(); // blocks and furniture are already placed
        // click is fired by items and furniture, so a block's variables are not promised to its rules
        assertThat(click.description().context()).extracting(variable -> variable.path())
                .contains("player.name", "item.amount", "furniture.id", "vars.*")
                .doesNotContain("block.id");
        assertThat(build().triggers().get(12).description().context()).isEmpty(); // tick: reserved
        assertThat(click.description().context()).filteredOn(variable -> variable.path().equals("item.amount"))
                .singleElement().satisfies(variable -> {
                    assertThat(variable.type()).isEqualTo(ParameterType.INTEGER);
                    assertThat(variable.hosts()).containsExactly("item");
                });
        assertThat(click.registeredBy()).isEqualTo("test");
    }

    @Test
    void actionsAreSortedAndCarryCategoryThreadingAvailabilityAndOwner() {
        logic.action(LogicFixture.recording("zeta", Threading.GLOBAL, new ArrayList<>()));
        logic.action(LogicFixture.recording("alpha", Threading.PLAYER_REGION, new ArrayList<>()));
        logic.action("example", new Unavailable());

        List<BehaviorVocabulary.Action> actions = build().actions();

        assertThat(actions).extracting(BehaviorVocabulary.Action::id)
                .containsExactly("alpha", "example:needs_vault", "zeta");
        BehaviorVocabulary.Action zeta = actions.get(2);
        assertThat(zeta.deferred()).isTrue();
        assertThat(zeta.threading()).isEqualTo(Threading.GLOBAL);
        assertThat(zeta.category()).isEqualTo(LogicCategory.DATA);
        assertThat(zeta.shorthand()).isEqualTo("value");
        assertThat(zeta.availability()).isEqualTo(Availability.AVAILABLE);
        assertThat(zeta.registeredBy()).isEqualTo("test");
        assertThat(actions.get(0).deferred()).isFalse();

        BehaviorVocabulary.Action greyed = actions.get(1);
        assertThat(greyed.availability().available()).isFalse();
        assertThat(greyed.availability().reason()).isEqualTo("No economy provider is installed");
    }

    @Test
    void conditionsFunctionsAndAddonCategoriesAppearAndAThrowingTypeNeverBreaksTheDescribeCall() {
        register(RegistryKeys.CONDITIONS, ConditionType.class, "example", "is_night", new IsNight());
        register(RegistryKeys.CONDITIONS, ConditionType.class, "example", "broken", new BrokenCondition());
        register(RegistryKeys.EXPRESSION_FUNCTIONS, ExpressionFunction.class, "example", "shout", new Shout());

        BehaviorVocabulary vocabulary = build();

        assertThat(vocabulary.conditions()).extracting(BehaviorVocabulary.Condition::id)
                .containsExactly("example:broken", "example:is_night");
        BehaviorVocabulary.Condition night = vocabulary.conditions().get(1);
        assertThat(night.description().category()).isEqualTo(LogicCategory.of("weather"));
        assertThat(night.availability()).isEqualTo(Availability.AVAILABLE);
        // the throwing condition is listed from its registry entry, and its availability check fails safe
        BehaviorVocabulary.Condition broken = vocabulary.conditions().get(0);
        assertThat(broken.description().self().displayName()).isEqualTo("Broken");
        assertThat(broken.availability().available()).isFalse();

        assertThat(vocabulary.functions()).singleElement().satisfies(function -> {
            assertThat(function.id()).isEqualTo("example:shout");
            assertThat(function.description().returns()).isEqualTo(ParameterType.STRING);
            assertThat(function.description().minArguments()).isEqualTo(1);
            assertThat(function.description().maxArguments()).isEqualTo(1);
        });
        assertThat(vocabulary.categories()).extracting(LogicCategory::id).containsExactly(
                "player", "gui", "world", "server", "economy", "data", "flow", "integration", "weather");
    }

    @Test
    void theStepKindsAreTheClosedGrammarAndTheInterpreterRunsAllOfIt() {
        List<BehaviorVocabulary.StepKind> steps = build().steps();

        assertThat(steps).extracting(BehaviorVocabulary.StepKind::id)
                .containsExactly("action", "if", "require", "wait", "stop", "call", "choose");
        assertThat(steps).filteredOn(BehaviorVocabulary.StepKind::supported)
                .extracting(BehaviorVocabulary.StepKind::id)
                .containsExactly("action", "if", "require", "wait", "stop", "call", "choose");
    }

    @Test
    void ruleAndActionOptionsStayTheVersionOneShape() {
        BehaviorVocabulary vocabulary = build();

        assertThat(vocabulary.ruleOptions()).extracting(ParameterSchema::id)
                .containsExactly("id", "cooldown", "charges", "per");
        assertThat(vocabulary.actionOptions()).extracting(ParameterSchema::id).containsExactly("chance", "delay");
        assertThat(BehaviorVocabulary.VERSION).isEqualTo(2);
    }

    private <T> void register(dev.materione.api.registry.RegistryKey key, Class<T> type, String namespace,
            String id, T entry) {
        SelfDescription self = entry instanceof BrokenCondition
                ? SelfDescription.of("Broken", "Throws")
                : SelfDescription.of(id, "A test entry");
        logic.registries().registry(key, type).register(NamespacedId.of(namespace, id), entry, self, namespace);
    }

    /** An action whose provider is missing. */
    private static final class Unavailable implements ActionType<Object> {
        @Override
        public String id() {
            return "needs_vault";
        }

        @Override
        public ActionDescription describe() {
            return ActionDescription.of(SelfDescription.of("Needs Vault", "Takes money"), LogicCategory.ECONOMY);
        }

        @Override
        public Threading threading() {
            return Threading.GLOBAL;
        }

        @Override
        public Object parse(ParamReader params) {
            return "";
        }

        @Override
        public ActionOutcome execute(Object params, LogicContext context) {
            return ActionOutcome.failed("no economy provider");
        }

        @Override
        public Availability availability() {
            return Availability.unavailable("No economy provider is installed");
        }
    }

    /** A condition in an addon-defined category. */
    private static final class IsNight implements ConditionType<Object> {
        @Override
        public String id() {
            return "is_night";
        }

        @Override
        public ConditionDescription describe() {
            return ConditionDescription.of(SelfDescription.of("Is night", "True at night"),
                    LogicCategory.of("weather"));
        }

        @Override
        public Object parse(ParamReader params) {
            return "";
        }

        @Override
        public boolean test(Object params, LogicContext context) {
            return true;
        }
    }

    /** A condition whose metadata throws. */
    private static final class BrokenCondition implements ConditionType<Object> {
        @Override
        public String id() {
            return "broken";
        }

        @Override
        public ConditionDescription describe() {
            throw new IllegalStateException("describe exploded");
        }

        @Override
        public Object parse(ParamReader params) {
            return "";
        }

        @Override
        public boolean test(Object params, LogicContext context) {
            return false;
        }

        @Override
        public Availability availability() {
            throw new IllegalStateException("availability exploded");
        }
    }

    /** {@code shout(s)} upper-cases its argument. */
    private static final class Shout implements ExpressionFunction {
        @Override
        public String id() {
            return "shout";
        }

        @Override
        public FunctionDescription describe() {
            return FunctionDescription.of(new SelfDescription("Shout", "Upper-cases text",
                    List.of(ParameterSchema.of("text", ParameterType.STRING, true)), "1.0.0"),
                    ParameterType.STRING);
        }

        @Override
        public Object call(List<Object> arguments, LogicContext context) {
            return String.valueOf(arguments.get(0)).toUpperCase(java.util.Locale.ROOT);
        }
    }
}
