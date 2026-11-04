package dev.materione.plugin.logic.recipe;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.GsonBuilder;
import dev.materione.api.ApiResult;
import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.logic.LogicAuthoringApi.InstantiateRequest;
import dev.materione.api.logic.variable.VariableDeclaration;
import dev.materione.api.logic.variable.VariableScope;
import dev.materione.api.logic.variable.VariableType;
import dev.materione.api.registry.BehaviorVocabulary;
import dev.materione.behavior.flow.FlowCompiler;
import dev.materione.behavior.logic.RegistryLogicLookup;
import dev.materione.behavior.logic.recipe.RecipeExpander;
import dev.materione.core.registry.RegistryService;
import dev.materione.plugin.logic.LogicService;
import dev.materione.plugin.logic.LogicTestSupport;
import dev.materione.testing.golden.GoldenFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The v1 recipes (logic.md §7.5, §30) as they ship: each loads from its YAML resource, expands with a fixed sample into
 * rules that compile against the real built-in registries, and matches its golden expansion under
 * {@code fixtures/golden/recipes/}. Regenerate with {@code -Dmaterione.golden.regen=true}; a changed expansion is a
 * reviewed change to the fixture.
 */
class BuiltinRecipesTest {

    private static final NamespacedId FLAG = NamespacedId.of("sample", "pvp");
    private static final VariableDeclaration FLAG_DECLARATION =
            new VariableDeclaration(FLAG, VariableScope.PLAYER, VariableType.BOOLEAN, false, "PvP toggle");

    /**
     * One sample per recipe. Insertion-ordered: the golden files record these maps, and their order must not depend
     * on the JVM's hash salt.
     */
    private static final Map<String, Map<String, Object>> SAMPLES = new LinkedHashMap<>();

    static {
        SAMPLES.put("pay_to_receive", sample("layer", "buy", "price", 250, "currency", "",
                "item", "minecraft:diamond", "amount", 2));
        SAMPLES.put("close_button", sample("layer", "close"));
        SAMPLES.put("welcome_message", sample("text", "<gold>Welcome to the shop!"));
        SAMPLES.put("confirm_dialog", sample("layer", "sell", "dialog", "sample:confirm"));
        SAMPLES.put("paged_player_list", sample("list", "roster", "previous", "prev", "next", "next"));
        SAMPLES.put("toggle_setting", sample("layer", "pvp", "variable", "sample:pvp"));
        SAMPLES.put("server_selector", sample("layer", "lobby", "server", "lobby"));
        SAMPLES.put("back_button", sample("layer", "back"));
    }

    @TempDir
    Path temp;

    @Test
    void theV1RecipesAreRegisteredAndListedInTheVocabulary() {
        LogicService service = LogicTestSupport.withBuiltIns(new RegistryService());

        BehaviorVocabulary vocabulary = service.vocabulary();

        assertThat(vocabulary.recipes()).extracting(BehaviorVocabulary.Recipe::id)
                .containsExactlyInAnyOrderElementsOf(BuiltinRecipes.IDS);
        BehaviorVocabulary.Recipe pay = vocabulary.recipes().stream()
                .filter(recipe -> recipe.id().equals("pay_to_receive")).findFirst().orElseThrow();
        assertThat(pay.description().name()).isEqualTo("Pay to receive");
        assertThat(pay.description().params()).extracting(param -> param.name())
                .containsExactly("layer", "price", "currency", "item", "amount", "message");
        assertThat(pay.registeredBy()).isEqualTo("materione");
    }

    @Test
    void theTrashSlotIsNotShippedBecauseItIsBlockedOn248() {
        assertThat(BuiltinRecipes.IDS).doesNotContain("trash_slot", "trash");
        assertThat(BuiltinRecipes.IDS).hasSameElementsAs(SAMPLES.keySet());
    }

    @Test
    void everyBuiltInExpandsToRulesThatValidateAndMatchItsGoldenFixture() throws IOException {
        Setup setup = Setup.create();
        for (String id : BuiltinRecipes.IDS) {
            ApiResult<List<Map<String, Object>>> result = setup.expander()
                    .expand(new InstantiateRequest(id, SAMPLES.get(id)));

            assertThat(result.ok()).as(id + " expands: " + messages(result)).isTrue();
            assertThat(result.value()).as(id).isNotEmpty();
            assertThat(FlowCompiler.compile(NamespacedId.of("materione", id), result.value(), null,
                    setup.lookup()).problems()).as(id + " validates").isEmpty();

            Path actual = temp.resolve(id + ".json");
            Files.writeString(actual, golden(id, result.value()), StandardCharsets.UTF_8);
            if (GoldenFiles.regenEnabled()) {
                GoldenFiles.writeGolden(actual, "recipes/" + id + ".json");
            }
            GoldenFiles.assertMatchesGolden(actual, "recipes/" + id + ".json");
        }
    }

    @Test
    void theOnlyWarningWithoutAnEconomyIsTheTakeMoneyOfThePaymentRecipe() {
        RecipeExpander expander = Setup.create().expander();

        ApiResult<List<Map<String, Object>>> pay = expander.expand(
                new InstantiateRequest("pay_to_receive", SAMPLES.get("pay_to_receive")));
        ApiResult<List<Map<String, Object>>> close = expander.expand(
                new InstantiateRequest("close_button", SAMPLES.get("close_button")));

        assertThat(pay.ok()).isTrue();
        assertThat(messages(pay)).hasSize(1);
        assertThat(messages(pay).get(0)).startsWith(
                "MTR-0088 recipe 'pay_to_receive' uses take_money, which this server cannot run now: ");
        assertThat(close.diagnostics()).isEmpty();
    }

    @Test
    void aBadParameterIsRefusedAndNothingIsExpanded() {
        RecipeExpander expander = Setup.create().expander();

        ApiResult<List<Map<String, Object>>> missing = expander.expand(new InstantiateRequest("pay_to_receive",
                Map.of("layer", "buy", "item", "minecraft:diamond")));
        ApiResult<List<Map<String, Object>>> undeclared = expander.expand(new InstantiateRequest("toggle_setting",
                Map.of("layer", "pvp", "variable", "sample:missing")));

        assertThat(missing.ok()).isFalse();
        assertThat(messages(missing)).containsExactly("MTR-0086 price is required");
        assertThat(undeclared.ok()).isFalse();
        assertThat(messages(undeclared)).containsExactly("MTR-0086 variable: 'sample:missing' is not a declared "
                + "variable (declare it in a variables file of its pack)");
    }

    // --- helpers ------------------------------------------------------------------------------

    /** Fresh real registries with the built-ins, and the lookup the expander compiles against (one PvP flag declared). */
    private record Setup(RegistryLogicLookup lookup) {

        static Setup create() {
            RegistryService registries = new RegistryService();
            LogicTestSupport.withBuiltIns(registries);
            return new Setup(new RegistryLogicLookup(registries,
                    id -> FLAG.equals(id) ? Optional.of(FLAG_DECLARATION) : Optional.empty()));
        }

        RecipeExpander expander() {
            return new RecipeExpander(lookup);
        }
    }

    private static Map<String, Object> sample(Object... keysAndValues) {
        var out = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            out.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return out;
    }

    private static String golden(String id, List<Map<String, Object>> rules) {
        var root = new LinkedHashMap<String, Object>();
        root.put("recipe", id);
        root.put("params", SAMPLES.get(id));
        root.put("rules", rules);
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root) + "\n";
    }

    private static List<String> messages(ApiResult<?> result) {
        return result.diagnostics().stream().map(BuiltinRecipesTest::line).toList();
    }

    private static String line(Diagnostic diagnostic) {
        return diagnostic.code().code() + " " + diagnostic.message();
    }
}
