package dev.materione.api.logic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.logic.variable.VariableScope;
import dev.materione.api.logic.variable.VariableType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A recipe written as data: its parse accepts and rejects, and its placeholders fill as logic.md §4.6 says. */
class RecipeTemplateTest {

    private static Map<String, Object> closeButton() {
        var definition = new LinkedHashMap<String, Object>();
        definition.put("name", "Close button");
        definition.put("description", "Closes the GUI");
        definition.put("params", Map.of("layer", Map.of("type", "string", "description", "the button")));
        definition.put("rules", List.of(Map.of(
                "on", "gui.click",
                "layer", "{{layer}}",
                "do", List.of("gui.close"))));
        return definition;
    }

    @Test
    void aTemplateParsesIntoItsDescriptionAndFillsItsRules() {
        RecipeTemplate recipe = RecipeTemplate.parse("close_button", closeButton());

        assertThat(recipe.id()).isEqualTo("close_button");
        assertThat(recipe.describe().name()).isEqualTo("Close button");
        assertThat(recipe.describe().param("layer").type()).isEqualTo(RecipeParamType.STRING);
        assertThat(recipe.expand(Map.of("layer", "close"))).containsExactly(Map.of(
                "on", "gui.click", "layer", "close", "do", List.of("gui.close")));
    }

    @Test
    void aWholePlaceholderKeepsTheTypeOfItsValue() {
        var definition = new HashMap<String, Object>();
        definition.put("name", "Pay");
        definition.put("params", Map.of(
                "price", Map.of("type", "number", "min", 0.01),
                "amount", Map.of("type", "integer", "default", 1)));
        definition.put("rules", List.of(Map.of("do", List.of(
                Map.of("take_money", Map.of("amount", "{{price}}")),
                Map.of("give_item", Map.of("amount", "{{amount}}"))))));
        RecipeTemplate recipe = RecipeTemplate.parse("pay", definition);

        var rule = recipe.expand(Map.of("price", 250.0, "amount", 2)).get(0);

        assertThat(rule.get("do")).isEqualTo(List.of(
                Map.of("take_money", Map.of("amount", 250.0)),
                Map.of("give_item", Map.of("amount", 2))));
    }

    @Test
    void aPlaceholderInsideTextTakesTheValueAsText() {
        var definition = new HashMap<String, Object>();
        definition.put("name", "Connect");
        definition.put("params", Map.of("server", Map.of("type", "string"), "count", Map.of("type", "number")));
        definition.put("rules", List.of(Map.of("do", List.of(
                Map.of("message", "<gray>Connecting to {{server}}, {{count}} online...")))));
        RecipeTemplate recipe = RecipeTemplate.parse("connect", definition);

        var steps = (List<?>) recipe.expand(Map.of("server", "lobby", "count", 12.0)).get(0).get("do");

        assertThat(steps.get(0)).isEqualTo(Map.of("message", "<gray>Connecting to lobby, 12 online..."));
    }

    @Test
    void aBlankOptionalTextRemovesItsKeyAndItsDefaultFillsIn() {
        var definition = new HashMap<String, Object>();
        definition.put("name", "Pay");
        definition.put("params", Map.of(
                "item", Map.of("type", "string"),
                "currency", Map.of("type", "string", "default", ""),
                "message", Map.of("type", "string", "default", "<red>No.")));
        definition.put("rules", List.of(Map.of(
                "do", List.of(Map.of(
                        "take_money", Map.of("amount", 5, "currency", "{{currency}}"),
                        "message", "{{message}}")))));
        RecipeTemplate recipe = RecipeTemplate.parse("pay", definition);

        var step = ((List<?>) recipe.expand(Map.of("item", "x")).get(0).get("do")).get(0);

        assertThat(step).isEqualTo(Map.of("take_money", Map.of("amount", 5), "message", "<red>No."));
    }

    @Test
    void aRequiredParameterWithoutAValueIsRefused() {
        RecipeTemplate recipe = RecipeTemplate.parse("close_button", closeButton());

        assertThatThrownBy(() -> recipe.expand(Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'layer' is required");
    }

    @Test
    void aVariableParameterDeclaresItsScopeAndValueType() {
        var definition = new HashMap<String, Object>();
        definition.put("name", "Toggle");
        definition.put("params", Map.of("variable", Map.of(
                "type", "variable", "scope", "player", "value_type", "boolean")));
        definition.put("rules", List.of(Map.of("do", List.of(
                Map.of("set_var", Map.of("var", "{{variable}}", "op", "toggle"))))));

        RecipeParam param = RecipeTemplate.parse("toggle", definition).describe().param("variable");

        assertThat(param.type()).isEqualTo(RecipeParamType.VARIABLE);
        assertThat(param.scope()).isEqualTo(VariableScope.PLAYER);
        assertThat(param.valueType()).isEqualTo(VariableType.BOOLEAN);
        assertThat(param.required()).isTrue();
    }

    @Test
    void aMalformedTemplateIsRejectedWithItsReason() {
        assertRejected(withTop("colour", "x"), "unknown key 'colour'");
        assertRejected(without("name"), "non-blank name");
        assertRejected(withRules(List.of()), "non-empty list of rules");
        assertRejected(withRules(List.of("gui.close")), "rule 1 is not a mapping");
        assertRejected(withRules(List.of(Map.of("layer", "{{nosuch}}"))), "'{{nosuch}}' names no declared parameter");
        assertRejected(withRules(List.of(Map.of("layer", "{{layer"))), "malformed placeholder");
        assertRejected(withParam("layer", Map.of("type", "colour")), "needs a type");
        assertRejected(withParam("layer", Map.of("type", "string", "colour", 1)), "unknown key 'colour'");
        assertRejected(withParam("amount", Map.of("type", "integer", "default", "lots")), "does not fit");
        assertRejected(withParam("slot", Map.of("type", "variable")), "needs scope player or server");
        assertRejected(withParam("Bad-Name", Map.of("type", "string")), "lower-case letters");
    }

    @Test
    void theTemplateIsCopiedSoALaterEditOfTheDefinitionCannotChangeIt() {
        var rule = new HashMap<String, Object>();
        rule.put("layer", "{{layer}}");
        var steps = new ArrayList<Object>(List.of("gui.close"));
        rule.put("do", steps);
        var definition = closeButton();
        definition.put("rules", new ArrayList<Object>(List.of(rule)));
        RecipeTemplate recipe = RecipeTemplate.parse("close_button", definition);

        rule.put("layer", "tampered");
        steps.add("gui.open");

        assertThat(recipe.expand(Map.of("layer", "a"))).containsExactly(Map.of(
                "layer", "a", "do", List.of("gui.close")));
    }

    // --- helpers ------------------------------------------------------------------------------

    private static void assertRejected(Map<String, Object> definition, String reason) {
        assertThatThrownBy(() -> RecipeTemplate.parse("bad", definition))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(reason);
    }

    private static Map<String, Object> withTop(String key, Object value) {
        var definition = closeButton();
        definition.put(key, value);
        return definition;
    }

    private static Map<String, Object> without(String key) {
        var definition = closeButton();
        definition.remove(key);
        return definition;
    }

    private static Map<String, Object> withRules(List<?> rules) {
        var definition = closeButton();
        definition.put("rules", rules);
        return definition;
    }

    private static Map<String, Object> withParam(String name, Map<String, Object> spec) {
        var definition = closeButton();
        definition.put("params", Map.of(name, spec));
        return definition;
    }
}
