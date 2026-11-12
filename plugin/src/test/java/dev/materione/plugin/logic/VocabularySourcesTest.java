package dev.materione.plugin.logic;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.ActionDescription;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.Availability;
import dev.materione.api.logic.InputMethod;
import dev.materione.api.logic.InputMethodDescription;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.Threading;
import dev.materione.api.registry.BehaviorVocabulary;
import dev.materione.api.registry.SelfDescription;
import dev.materione.core.registry.RegistryService;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * The vocabulary carries the source and method registries the node inspector reads (logic.md section 31): list
 * sources with their entry schema, item sources, input methods with their availability, and the actions that take an
 * {@code else:}. All of it is read from the registries, so an addon's entry appears beside the built-ins.
 */
class VocabularySourcesTest {

    private final RegistryService registries = new RegistryService();
    private final LogicService service = LogicTestSupport.withBuiltIns(registries);

    @Test
    void theBuiltInListAndItemSourcesAreListedWithTheirEntrySchemaAndShorthand() {
        BehaviorVocabulary vocabulary = service.vocabulary();

        assertThat(vocabulary.listSources()).extracting(BehaviorVocabulary.ListSourceEntry::id)
                .containsExactly("content", "online_players", "pack_items", "static", "variable");
        BehaviorVocabulary.ListSourceEntry players = vocabulary.listSources().get(1);
        assertThat(players.registeredBy()).isEqualTo("materione");
        assertThat(players.description().entry()).extracting(variable -> variable.path()).contains("entry.name");
        assertThat(vocabulary.listSources().get(2).description().shorthand()).isEqualTo("pack");

        assertThat(vocabulary.itemSources()).extracting(BehaviorVocabulary.ItemSourceEntry::id)
                .containsExactly("head", "item", "player_slot");
        assertThat(vocabulary.itemSources().get(2).description().shorthand()).isEqualTo("slot");
    }

    @Test
    void anInputMethodIsListedWithItsAvailabilityAndAWindowFlag() {
        var addon = LogicTestSupport.owner("example");
        service.inputMethods().register(addon, NamespacedId.of("example", "dialog"), new FakeMethod("dialog", false, true));
        service.inputMethods().register(addon, NamespacedId.of("example", "sign"), new FakeMethod("sign", true, false));

        BehaviorVocabulary vocabulary = service.vocabulary();

        assertThat(vocabulary.inputMethods()).extracting(BehaviorVocabulary.InputMethodEntry::id)
                .containsExactly("example:dialog", "example:sign");
        BehaviorVocabulary.InputMethodEntry dialog = vocabulary.inputMethods().get(0);
        assertThat(dialog.description().closesWindow()).isFalse();
        assertThat(dialog.availability()).isEqualTo(Availability.AVAILABLE);
        assertThat(dialog.registeredBy()).isEqualTo("example");
        BehaviorVocabulary.InputMethodEntry sign = vocabulary.inputMethods().get(1);
        assertThat(sign.availability().available()).isFalse();
        assertThat(sign.availability().reason()).isEqualTo("needs the 26.x API");
    }

    @Test
    void anActionThatTakesAnElseSaysSoAndOthersDoNot() {
        service.actions().register(LogicTestSupport.owner("example"), NamespacedId.of("example", "ask"), new Asking());

        BehaviorVocabulary vocabulary = service.vocabulary();

        assertThat(vocabulary.actions()).filteredOn(BehaviorVocabulary.Action::takesElse)
                .extracting(BehaviorVocabulary.Action::id).containsExactly("custody.consume", "example:ask");
        assertThat(vocabulary.actions()).filteredOn(action -> action.id().equals("message"))
                .singleElement().satisfies(message -> assertThat(message.takesElse()).isFalse());
    }

    /** A method that is unavailable when told to be. */
    private record FakeMethod(String id, boolean unavailable, boolean keepsWindow) implements InputMethod {
        @Override
        public InputMethodDescription describe() {
            return InputMethodDescription.of(SelfDescription.of(id, "A test method"), !keepsWindow);
        }

        @Override
        public Availability availability() {
            return unavailable ? Availability.unavailable("needs the 26.x API") : Availability.AVAILABLE;
        }

        @Override
        public void ask(Player player, String prompt, Reply reply) {}

        @Override
        public void dismiss(Player player) {}
    }

    /** An action that asks a question. */
    private static final class Asking implements ActionType<Object> {
        @Override
        public String id() {
            return "ask";
        }

        @Override
        public ActionDescription describe() {
            return ActionDescription.of(SelfDescription.of("Ask", "Asks"), LogicCategory.GUI);
        }

        @Override
        public Threading threading() {
            return Threading.ANY;
        }

        @Override
        public Object parse(ParamReader params) {
            return "";
        }

        @Override
        public ActionOutcome execute(Object params, LogicContext context) {
            return ActionOutcome.OK;
        }

        @Override
        public boolean takesElse() {
            return true;
        }
    }
}
