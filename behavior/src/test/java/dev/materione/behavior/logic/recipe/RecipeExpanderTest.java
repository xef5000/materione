package dev.materione.behavior.logic.recipe;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.ApiResult;
import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.logic.ActionDescription;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.Availability;
import dev.materione.api.logic.LogicAuthoringApi.InstantiateRequest;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.Recipe;
import dev.materione.api.logic.RecipeDescription;
import dev.materione.api.logic.RecipeParam;
import dev.materione.api.logic.RecipeTemplate;
import dev.materione.api.logic.Threading;
import dev.materione.api.logic.variable.VariableDeclaration;
import dev.materione.api.logic.variable.VariableScope;
import dev.materione.api.logic.variable.VariableType;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.behavior.logic.LogicLookup;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The expansion pipeline: parameters checked and typed, the rules compiled, availability reported (logic.md §30). */
class RecipeExpanderTest {

    private static final NamespacedId FLAG = NamespacedId.of("shop", "pvp");
    private static final VariableDeclaration FLAG_DECLARATION = new VariableDeclaration(
            FLAG, VariableScope.PLAYER, VariableType.BOOLEAN, false, "PvP");

    private final LogicLookup lookup = lookup(Map.of(
            "give", new Fake("give", Availability.AVAILABLE),
            "message", new Fake("message", Availability.AVAILABLE),
            "gui.close", new Fake("gui.close", Availability.AVAILABLE),
            "take_money", new Fake("take_money", Availability.unavailable("no economy is installed"))),
            Map.of("materione:pay", template()));

    private final RecipeExpander expander = new RecipeExpander(lookup);

    @Test
    void aValidRequestExpandsToRulesThatCompileAndCarryNoProblem() {
        ApiResult<List<Map<String, Object>>> result = expander.expand(
                new InstantiateRequest("pay", Map.of("layer", "buy", "item", "gem", "price", 250)));

        assertThat(result.ok()).isTrue();
        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.value()).containsExactly(Map.of(
                "on", "gui.click", "layer", "buy",
                "do", List.of(Map.of("give", "gem"), "gui.close")));
    }

    @Test
    void theTypedParametersAreWhatTheExpansionReceives() {
        var seen = new HashMap<String, Object>();
        Recipe recipe = new Recipe() {
            @Override
            public String id() {
                return "probe";
            }

            @Override
            public RecipeDescription describe() {
                return new RecipeDescription("Probe", "", List.of(
                        RecipeParam.optional("count", dev.materione.api.logic.RecipeParamType.INTEGER, 3, ""),
                        RecipeParam.optional("price", dev.materione.api.logic.RecipeParamType.NUMBER, 1, "")));
            }

            @Override
            public List<Map<String, Object>> expand(Map<String, Object> params) {
                seen.putAll(params);
                return List.of(Map.of("on", "gui.click", "do", List.of("gui.close")));
            }
        };
        var probing = new RecipeExpander(lookup(Map.of("gui.close", new Fake("gui.close", Availability.AVAILABLE)),
                Map.of("materione:probe", recipe)));

        probing.expand(new InstantiateRequest("probe", Map.of("price", 5)));

        assertThat(seen).containsEntry("count", 3).containsEntry("price", 5.0);
    }

    @Test
    void anUnknownRecipeIsRefusedAsUnknown() {
        ApiResult<List<Map<String, Object>>> result = expander.expand(new InstantiateRequest("nosuch", Map.of()));

        assertThat(result.ok()).isFalse();
        assertThat(result.diagnostics().get(0).code().code()).isEqualTo("MTR-0008");
        assertThat(result.diagnostics().get(0).message()).contains("unknown recipe 'nosuch'");
    }

    @Test
    void aMissingRequiredParameterIsRefusedWithItsName() {
        ApiResult<List<Map<String, Object>>> result = expander.expand(
                new InstantiateRequest("pay", Map.of("item", "gem")));

        assertThat(messages(result)).containsExactly("MTR-0086 layer is required",
                "MTR-0086 price is required");
    }

    @Test
    void everyProblemIsReportedAtOnceAndNothingIsExpanded() {
        ApiResult<List<Map<String, Object>>> result = expander.expand(new InstantiateRequest("pay",
                Map.of("layer", "buy", "item", " ", "price", 0, "extra", true)));

        assertThat(result.ok()).isFalse();
        assertThat(messages(result)).containsExactly(
                "MTR-0086 'extra' is not a parameter of Pay, which takes layer, item, price",
                "MTR-0086 item must not be blank",
                "MTR-0086 price must be at least 1");
    }

    @Test
    void aWrongTypeIsRefused() {
        ApiResult<List<Map<String, Object>>> result = expander.expand(new InstantiateRequest("pay",
                Map.of("layer", "buy", "item", "gem", "price", "lots")));

        assertThat(messages(result)).containsExactly("MTR-0086 price must be a number");
    }

    @Test
    void aVariableParameterMustBeDeclaredWithTheRightScopeAndType() {
        var toggle = new RecipeExpander(lookup(Map.of("set_var", new Fake("set_var", Availability.AVAILABLE)),
                Map.of("materione:toggle", toggleRecipe())));

        assertThat(toggle.expand(new InstantiateRequest("toggle", Map.of("variable", "shop:pvp"))).ok()).isTrue();
        assertThat(messages(toggle.expand(new InstantiateRequest("toggle", Map.of("variable", "shop:nope")))))
                .containsExactly("MTR-0086 variable: 'shop:nope' is not a declared variable "
                        + "(declare it in a variables file of its pack)");
        assertThat(messages(toggle.expand(new InstantiateRequest("toggle", Map.of("variable", "bare")))))
                .containsExactly("MTR-0086 variable must name a variable as pack:key");
    }

    @Test
    void anExpansionThatDoesNotValidateIsRefusedWithTheCompilerProblems() {
        var broken = new RecipeExpander(lookup(Map.of(), Map.of("materione:broken", new Recipe() {
            @Override
            public String id() {
                return "broken";
            }

            @Override
            public RecipeDescription describe() {
                return new RecipeDescription("Broken", "", List.of());
            }

            @Override
            public List<Map<String, Object>> expand(Map<String, Object> params) {
                return List.of(Map.of("on", "gui.click", "do", List.of(Map.of("nosuch", 1))));
            }
        })));

        ApiResult<List<Map<String, Object>>> result = broken.expand(new InstantiateRequest("broken", Map.of()));

        assertThat(result.ok()).isFalse();
        assertThat(result.diagnostics().get(0).code().code()).isEqualTo("MTR-0087");
        assertThat(result.diagnostics()).extracting(diagnostic -> diagnostic.code().code())
                .contains("MTR-0057");
    }

    @Test
    void anExpansionThatThrowsIsRefusedNotPropagated() {
        var throwing = new RecipeExpander(lookup(Map.of(), Map.of("materione:throws", new Recipe() {
            @Override
            public String id() {
                return "throws";
            }

            @Override
            public RecipeDescription describe() {
                return new RecipeDescription("Throws", "", List.of());
            }

            @Override
            public List<Map<String, Object>> expand(Map<String, Object> params) {
                throw new IllegalStateException("boom");
            }
        })));

        ApiResult<List<Map<String, Object>>> result = throwing.expand(new InstantiateRequest("throws", Map.of()));

        assertThat(messages(result)).containsExactly("MTR-0087 recipe 'throws' failed to expand: boom");
    }

    @Test
    void anActionThisServerCannotRunIsAWarningThatTravelsWithTheExpansion() {
        ActionType<?> unpaid = new Fake("take_money", Availability.unavailable("no economy is installed"));
        List<Object> steps = List.of(Map.of("take_money", 5), Map.of("take_money", 6));
        Map<String, Object> rule = Map.of("on", "gui.click", "do", steps);
        Recipe recipe = RecipeTemplate.parse("paid", Map.of("name", "Paid", "params", Map.of(), "rules", List.of(rule)));
        var paid = new RecipeExpander(lookup(Map.of("take_money", unpaid), Map.of("materione:paid", recipe)));

        ApiResult<List<Map<String, Object>>> result = paid.expand(new InstantiateRequest("paid", Map.of()));

        assertThat(result.ok()).isTrue();
        assertThat(result.value()).hasSize(1);
        assertThat(messages(result)).containsExactly(
                "MTR-0088 recipe 'paid' uses take_money, which this server cannot run now: no economy is installed");
    }

    // --- fixtures ------------------------------------------------------------------------------

    /** The v1-shaped recipe: a click gives an item, then closes; the price is typed and shown on the rule. */
    private static RecipeTemplate template() {
        // ordered: a recipe lists its parameters in the order it declares them, as a YAML file does
        var params = new LinkedHashMap<String, Object>();
        params.put("layer", Map.of("type", "string"));
        params.put("item", Map.of("type", "string"));
        params.put("price", Map.of("type", "number", "min", 1));
        return RecipeTemplate.parse("pay", Map.of(
                "name", "Pay",
                "params", params,
                "rules", List.of(Map.of(
                        "on", "gui.click",
                        "layer", "{{layer}}",
                        "do", List.of(Map.of("give", "{{item}}"), "gui.close")))));
    }

    private static RecipeTemplate toggleRecipe() {
        return RecipeTemplate.parse("toggle", Map.of(
                "name", "Toggle",
                "params", Map.of("variable", Map.of(
                        "type", "variable", "scope", "player", "value_type", "boolean")),
                "rules", List.of(Map.of("on", "gui.click", "do", List.of(
                        Map.of("set_var", Map.of("var", "{{variable}}", "op", "toggle")))))));
    }

    private static List<String> messages(ApiResult<?> result) {
        return result.diagnostics().stream().map(RecipeExpanderTest::line).toList();
    }

    private static String line(Diagnostic diagnostic) {
        return diagnostic.code().code() + " " + diagnostic.message();
    }

    private static LogicLookup lookup(Map<String, ActionType<?>> actions, Map<String, Recipe> recipes) {
        return new LogicLookup() {
            @Override
            public Optional<ActionType<?>> action(String authoredId) {
                return Optional.ofNullable(actions.get(authoredId));
            }

            @Override
            public Optional<dev.materione.api.logic.ConditionType<?>> condition(String authoredId) {
                return Optional.empty();
            }

            @Override
            public Optional<Recipe> recipe(String authoredId) {
                return Optional.ofNullable(recipes.get(authoredId.contains(":") ? authoredId : "materione:" + authoredId));
            }

            @Override
            public boolean triggerKnown(String authoredId) {
                return authoredId.equals("gui.click") || authoredId.equals("gui.open");
            }

            @Override
            public Optional<VariableDeclaration> variable(NamespacedId id) {
                return FLAG.equals(id) ? Optional.of(FLAG_DECLARATION) : Optional.empty();
            }
        };
    }

    /** An action that takes one value and runs nothing; the expander only needs it to resolve and parse. */
    private static final class Fake implements ActionType<String> {
        private final String id;
        private final Availability availability;

        Fake(String id, Availability availability) {
            this.id = id;
            this.availability = availability;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public ActionDescription describe() {
            return new ActionDescription(new SelfDescription(id, "fake",
                    List.of(ParameterSchema.of("value", ParameterType.STRING, false)), "1.0.0"),
                    LogicCategory.DATA, "value");
        }

        @Override
        public Threading threading() {
            return Threading.ANY;
        }

        @Override
        public String parse(ParamReader params) {
            return params.raw("value").map(String::valueOf).orElse("");
        }

        @Override
        public ActionOutcome execute(String params, LogicContext context) {
            return ActionOutcome.OK;
        }

        @Override
        public Availability availability() {
            return availability;
        }
    }
}
