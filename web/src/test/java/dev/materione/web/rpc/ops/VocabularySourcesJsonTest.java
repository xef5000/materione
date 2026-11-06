package dev.materione.web.rpc.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import dev.materione.api.logic.Availability;
import dev.materione.api.logic.ContextVariable;
import dev.materione.api.logic.InputMethodDescription;
import dev.materione.api.logic.ItemSourceDescription;
import dev.materione.api.logic.ListSourceDescription;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.registry.BehaviorVocabulary;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The vocabulary wire shape for the registries the node inspector reads (logic.md section 31): additive keys of the
 * version-2 vocabulary, so an editor that predates them reads the same payload unchanged.
 */
class VocabularySourcesJsonTest {

    private static BehaviorVocabulary vocabulary() {
        var asking = new BehaviorVocabulary.Action("prompt", SelfDescription.of("Prompt", "Asks"), null, false,
                LogicCategory.GUI, null, Availability.AVAILABLE, "materione", true);
        var plain = new BehaviorVocabulary.Action("message", SelfDescription.of("Message", "Says"), "text", false);
        return new BehaviorVocabulary(
                List.of(), List.of(asking, plain), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(),
                List.of(new BehaviorVocabulary.ListSourceEntry("online_players", new ListSourceDescription(
                        SelfDescription.of("Online players", "Everyone online"), null,
                        List.of(ContextVariable.of("entry.name", ParameterType.STRING, "The player's name")), false),
                        "materione")),
                List.of(new BehaviorVocabulary.ItemSourceEntry("player_slot", ItemSourceDescription.of(
                        SelfDescription.of("Player slot", "A slot of the viewer"), "slot"), "materione")),
                List.of(
                        new BehaviorVocabulary.InputMethodEntry("chat", InputMethodDescription.of(
                                SelfDescription.of("Chat", "A chat line"), true), Availability.AVAILABLE, "materione"),
                        new BehaviorVocabulary.InputMethodEntry("sign", InputMethodDescription.of(
                                SelfDescription.of("Sign", "A sign editor"), true),
                                Availability.unavailable("needs the 26.x API"), "materione")));
    }

    @Test
    void takesElseIsOnlyWrittenForTheActionsThatAskAQuestion() {
        JsonObject json = EditorContentOps.vocabularyJson(vocabulary());

        var actions = json.getAsJsonArray("actions");
        assertThat(actions.get(0).getAsJsonObject().get("takesElse").getAsBoolean()).isTrue();
        assertThat(actions.get(1).getAsJsonObject().has("takesElse")).isFalse();
    }

    @Test
    void sourcesAndMethodsCarryTheirEntrySchemaShorthandAndAvailability() {
        JsonObject json = EditorContentOps.vocabularyJson(vocabulary());

        JsonObject list = json.getAsJsonArray("listSources").get(0).getAsJsonObject();
        assertThat(list.get("id").getAsString()).isEqualTo("online_players");
        assertThat(list.get("displayName").getAsString()).isEqualTo("Online players");
        assertThat(list.get("cached").getAsBoolean()).isFalse();
        assertThat(list.getAsJsonArray("entry").get(0).getAsJsonObject().get("path").getAsString())
                .isEqualTo("entry.name");

        JsonObject item = json.getAsJsonArray("itemSources").get(0).getAsJsonObject();
        assertThat(item.get("shorthand").getAsString()).isEqualTo("slot");

        var methods = json.getAsJsonArray("inputMethods");
        assertThat(methods.get(0).getAsJsonObject().get("available").getAsBoolean()).isTrue();
        assertThat(methods.get(0).getAsJsonObject().get("closesWindow").getAsBoolean()).isTrue();
        JsonObject sign = methods.get(1).getAsJsonObject();
        assertThat(sign.get("available").getAsBoolean()).isFalse();
        assertThat(sign.get("unavailableReason").getAsString()).isEqualTo("needs the 26.x API");
    }

    @Test
    void anOlderVocabularyShapeStillWritesEmptySections() {
        JsonObject json = EditorContentOps.vocabularyJson(BehaviorVocabulary.empty());

        assertThat(json.getAsJsonArray("listSources")).isEmpty();
        assertThat(json.getAsJsonArray("itemSources")).isEmpty();
        assertThat(json.getAsJsonArray("inputMethods")).isEmpty();
    }
}
