package dev.materione.plugin.logic;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.logic.ConditionType;
import dev.materione.api.registry.BehaviorVocabulary;
import dev.materione.api.registry.RegistryKeys;
import dev.materione.core.registry.RegistryService;
import dev.materione.plugin.logic.support.ItemCatalog;
import dev.materione.plugin.logic.support.OpenGuis;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The built-in conditions as the vocabulary and the registry see them (logic.md §4.2, §19): registered through the
 * public API under {@code materione:}, with their category and shorthand, and no composite name among them.
 */
class BuiltinConditionsTest {

    /** Only the eight in #210's scope: {@code var} waits for the variables API (#211), {@code slot_has} for #218. */
    private static final Set<String> EXPECTED = Set.of("has_permission", "has_money", "has_exp", "has_item",
            "has_item_space", "is_near", "in_world", "in_gui");

    private static final Set<String> COMPOSITES = Set.of("all", "any", "not", "at_least");

    private final RegistryService registries = new RegistryService();
    private final LogicService service = LogicTestSupport.withBuiltIns(registries);

    @Test
    void theEightBuiltInConditionsAreRegisteredUnderMaterioneWithTheirCategoriesAndShorthands() {
        Map<String, BehaviorVocabulary.Condition> byId = service.vocabulary().conditions().stream()
                .collect(Collectors.toMap(BehaviorVocabulary.Condition::id, condition -> condition));

        assertThat(byId.keySet()).containsAll(EXPECTED);
        assertThat(byId.get("has_permission").registeredBy()).isEqualTo("materione");
        assertThat(byId.get("has_permission").description().shorthand()).isEqualTo("permission");
        assertThat(byId.get("has_permission").description().category().id()).isEqualTo("player");
        assertThat(byId.get("has_money").description().shorthand()).isEqualTo("amount");
        assertThat(byId.get("has_money").description().category().id()).isEqualTo("economy");
        assertThat(byId.get("has_exp").description().shorthand()).isEqualTo("amount");
        assertThat(byId.get("has_item").description().shorthand()).isEqualTo("item");
        assertThat(byId.get("has_item_space").description().shorthand()).isEqualTo("slots");
        assertThat(byId.get("in_world").description().shorthand()).isEqualTo("worlds");
        assertThat(byId.get("in_world").description().category().id()).isEqualTo("world");
        assertThat(byId.get("in_gui").description().shorthand()).isEqualTo("id");
        assertThat(byId.get("in_gui").description().category().id()).isEqualTo("gui");
        assertThat(byId.get("is_near").description().shorthand()).isNull();
        assertThat(byId.get("is_near").description().category().id()).isEqualTo("world");
    }

    @Test
    void noBuiltInConditionTakesACompositeName() {
        var ids = BuiltinLogic.conditions(service.providers(), OpenGuis.none(), ItemCatalog.none()).stream()
                .map(ConditionType::id)
                .toList();
        assertThat(ids).containsExactlyInAnyOrderElementsOf(EXPECTED);
        assertThat(ids).doesNotContainAnyElementsOf(COMPOSITES);
    }

    @Test
    void theRegistryResolvesEveryBuiltInAndNoCompositeName() {
        for (String id : EXPECTED) {
            assertThat(service.conditions().resolve(id)).as(id).isPresent();
        }
        for (String composite : COMPOSITES) {
            assertThat(service.conditions().resolve(composite)).as(composite).isEmpty();
        }
        assertThat(registries.byKey(RegistryKeys.CONDITIONS)).isPresent();
    }

    @Test
    void aConditionRegisteredByAnAddonLeavesWithItsOwnerAndTheBuiltInsStay() {
        var addon = LogicTestSupport.owner("example");
        service.conditions().register(addon, dev.materione.api.NamespacedId.of("example", "is_night"),
                new IsNightStub());
        assertThat(service.vocabulary().conditions()).extracting(BehaviorVocabulary.Condition::id)
                .contains("example:is_night");

        service.ownerDisabled(addon);

        assertThat(service.vocabulary().conditions()).extracting(BehaviorVocabulary.Condition::id)
                .doesNotContain("example:is_night")
                .containsAll(EXPECTED);
    }

    /** A condition that is never asked; it only needs to be registrable. */
    private static final class IsNightStub implements ConditionType<Void> {
        @Override
        public String id() {
            return "is_night";
        }

        @Override
        public dev.materione.api.logic.ConditionDescription describe() {
            return dev.materione.api.logic.ConditionDescription.of(
                    new dev.materione.api.registry.SelfDescription("Is night", "Night time", List.of(), "2.0.0"),
                    dev.materione.api.logic.LogicCategory.WORLD);
        }

        @Override
        public Void parse(dev.materione.api.logic.ParamReader params) {
            return null;
        }

        @Override
        public boolean test(Void params, dev.materione.api.logic.LogicContext context) {
            return false;
        }
    }
}
