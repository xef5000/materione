package dev.materione.api.logic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.NamespacedId;
import dev.materione.api.registry.BehaviorVocabulary;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The small value types of the logic SPI hold their invariants. */
class LogicTypesTest {

    private static final SelfDescription SELF = new SelfDescription("Thing", "A thing",
            List.of(ParameterSchema.of("amount", ParameterType.INTEGER, true)), "1.0.0");

    @Test
    void outcomesAreOkOrFailedWithAReason() {
        assertThat(ActionOutcome.OK.isOk()).isTrue();
        assertThat(ActionOutcome.failed("not enough money").isOk()).isFalse();
        assertThat(ActionOutcome.failed("not enough money"))
                .isEqualTo(new ActionOutcome.Failed("not enough money"));
        assertThatThrownBy(() -> ActionOutcome.failed(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void availabilityMustExplainItself() {
        assertThat(Availability.AVAILABLE.available()).isTrue();
        assertThat(Availability.unavailable("No Vault").reason()).isEqualTo("No Vault");
        assertThatThrownBy(() -> new Availability(false, "")).isInstanceOf(IllegalArgumentException.class);
        assertThat(new Availability(true, "ignored").reason()).isEmpty();
    }

    @Test
    void aShorthandMustNameADeclaredParameter() {
        assertThat(new ActionDescription(SELF, LogicCategory.ECONOMY, "amount").shorthand()).isEqualTo("amount");
        assertThat(ActionDescription.of(SELF, LogicCategory.ECONOMY).shorthand()).isNull();
        assertThat(new ActionDescription(SELF, LogicCategory.ECONOMY, " ").shorthand()).isNull();
        assertThatThrownBy(() -> new ActionDescription(SELF, LogicCategory.ECONOMY, "sum"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("'sum'");
        assertThatThrownBy(() -> new ConditionDescription(SELF, LogicCategory.ECONOMY, "sum"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void categoriesAreSlugsWithEightBuiltIns() {
        assertThat(LogicCategory.builtIn()).extracting(LogicCategory::id).containsExactly(
                "player", "gui", "world", "server", "economy", "data", "flow", "integration");
        assertThat(LogicCategory.PLAYER.isBuiltIn()).isTrue();
        assertThat(LogicCategory.of("weather")).isEqualTo(new LogicCategory("weather", "Weather"));
        assertThat(LogicCategory.of("weather").isBuiltIn()).isFalse();
        assertThatThrownBy(() -> LogicCategory.of("Not A Slug")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void facetsAreIdentifiedByNamespacedIdAlone() {
        ContextFacet<String> note = ContextFacet.of("test:note", String.class);
        assertThat(note).isEqualTo(ContextFacet.of(NamespacedId.of("test", "note"), String.class));
        assertThat(note.hashCode()).isEqualTo(ContextFacet.of("test:note", String.class).hashCode());
        assertThat(note).isNotEqualTo(ContextFacet.of("test:other", String.class));
        assertThat(note.cast("x")).isEqualTo("x");
        assertThat(Facets.CANCELLATION).isSameAs(Cancellation.FACET);
        assertThat(Facets.PLAYER.id()).isEqualTo(NamespacedId.of("materione", "player"));
    }

    @Test
    void contextVariablePathsAreDottedLowercaseWithAnOptionalWildcard() {
        assertThat(ContextVariable.of("gui.args.*", ParameterType.STRING, "args").hosts()).isEmpty();
        assertThat(ContextVariable.of("player.name", ParameterType.STRING, null).providedBy("item").hosts())
                .containsExactly("item");
        assertThatThrownBy(() -> ContextVariable.of("Player.Name", ParameterType.STRING, ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ContextVariable.of("a..b", ParameterType.STRING, ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void functionArityComesFromTheParameterList() {
        var fixed = FunctionDescription.of(new SelfDescription("Min", "Smallest",
                List.of(ParameterSchema.of("a", ParameterType.DECIMAL, true),
                        ParameterSchema.of("b", ParameterType.DECIMAL, false)), "1.0.0"), ParameterType.DECIMAL);
        assertThat(fixed.minArguments()).isEqualTo(1);
        assertThat(fixed.maxArguments()).isEqualTo(2);

        var variadic = new FunctionDescription(new SelfDescription("Max", "Largest",
                List.of(ParameterSchema.of("n", ParameterType.DECIMAL, true)), "1.0.0"), ParameterType.DECIMAL, true);
        assertThat(variadic.maxArguments()).isEqualTo(-1);
        assertThatThrownBy(() -> new FunctionDescription(SelfDescription.of("x", "y"), ParameterType.STRING, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aTriggerIsFiredOnlyWhenSomeRuntimeFiresIt() {
        var fired = new TriggerDescription(SelfDescription.of("Click", "Clicks"),
                List.of("left"), List.of("item"), List.of(), List.of(), true);
        var reserved = new TriggerDescription(SelfDescription.of("Tick", "Reserved"),
                null, null, null, null, false);
        assertThat(fired.fired()).isTrue();
        assertThat(reserved.fired()).isFalse();
        assertThat(reserved.context()).isEmpty();
    }

    @Test
    void theVersionOneVocabularyShapeStillBuildsAndGainsEmptyVersionTwoSections() {
        var v1 = new BehaviorVocabulary(List.of(), List.of(
                new BehaviorVocabulary.Action("message", SELF, "text", false)),
                List.of(), List.of(), List.of());

        assertThat(v1.triggers()).isEmpty();
        assertThat(v1.conditions()).isEmpty();
        assertThat(v1.steps()).isEmpty();
        assertThat(v1.categories()).isEmpty();
        assertThat(v1.functions()).isEmpty();
        assertThat(v1.actions().get(0).availability()).isEqualTo(Availability.AVAILABLE);
        assertThat(v1.actions().get(0).category()).isNull();
        assertThat(v1.withMechanics(List.of()).actions()).hasSize(1);
        assertThat(BehaviorVocabulary.empty().events()).isEmpty();
    }

    @Test
    void aContextAnswersFacetsRequireAndDottedValues() {
        ContextFacet<String> note = ContextFacet.of("test:note", String.class);
        LogicContext context = new LogicContext() {
            @Override
            public <T> Optional<T> facet(ContextFacet<T> facet) {
                return facet.equals(note) ? Optional.of(facet.cast("hello")) : Optional.empty();
            }

            @Override
            public Object resolve(List<String> path) {
                return String.join("/", path);
            }

            @Override
            public Map<String, Object> locals() {
                return Map.of();
            }
        };

        assertThat(context.has(note)).isTrue();
        assertThat(context.require(note)).isEqualTo("hello");
        assertThat(context.value("player.level")).isEqualTo("player/level");
        assertThat(context.applyPlaceholders("%x%")).isEqualTo("%x%");
        assertThatThrownBy(() -> context.require(Cancellation.FACET))
                .isInstanceOf(MissingFacetException.class)
                .hasMessageContaining("materione:cancellation");
    }
}
