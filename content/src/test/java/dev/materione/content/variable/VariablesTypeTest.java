package dev.materione.content.variable;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.variable.VariableDeclaration;
import dev.materione.api.logic.variable.VariableScope;
import dev.materione.api.logic.variable.VariableType;
import dev.materione.api.registry.ParameterType;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class VariablesTypeTest {

    private final VariablesType type = new VariablesType();
    private final Map<String, Map<String, Object>> templates = Map.of();

    private static Map<String, Object> definition(Object... keyValues) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static NamespacedId id(String value) {
        return NamespacedId.of("shop", value);
    }

    @Nested
    class Schema {

        @Test
        void theTypeIsNamedVariableAndLivesInVariablesDirectory() {
            assertThat(type.name()).isEqualTo("variable");
            assertThat(type.directory()).isEqualTo("variables");
        }

        @Test
        void theSelfDescriptionListsTheKeysWithTheirChoices() {
            var parameters = type.describe().parameters();

            assertThat(parameters).extracting(p -> p.id())
                    .containsExactly("scope", "type", "default", "description");
            assertThat(parameters.get(0).required()).isTrue();
            assertThat(parameters.get(0).allowedValues()).containsExactly("player", "server");
            assertThat(parameters.get(1).allowedValues()).containsExactly("number", "string", "boolean");
            assertThat(parameters.get(2).type()).isEqualTo(ParameterType.RAW);
        }
    }

    @Nested
    class Parse {

        @Test
        void aPlayerNumberWithDefaultAndDescription() {
            var parsed = type.parse(id("coins"), definition(
                    "scope", "player", "type", "number", "default", 0, "description", "Shop coins"), templates);

            assertThat(parsed).contains(new VariableDeclaration(
                    id("coins"), VariableScope.PLAYER, VariableType.NUMBER, 0.0d, "Shop coins"));
        }

        @Test
        void aServerStringDefaultsToTheEmptyText() {
            var parsed = type.parse(id("motd"), definition("scope", "server", "type", "string"), templates);

            assertThat(parsed.orElseThrow()).isInstanceOfSatisfying(VariableDeclaration.class, declaration -> {
                assertThat(declaration.scope()).isEqualTo(VariableScope.SERVER);
                assertThat(declaration.defaultValue()).isEqualTo("");
                assertThat(declaration.description()).isEmpty();
            });
        }

        @Test
        void aBooleanDefaultsToFalse() {
            var parsed = type.parse(id("claimed"), definition("scope", "player", "type", "boolean"), templates);

            assertThat(parsed.orElseThrow()).isInstanceOfSatisfying(VariableDeclaration.class,
                    declaration -> assertThat(declaration.defaultValue()).isEqualTo(false));
        }

        @Test
        void rejectsAnUnknownScopeOrType() {
            assertThat(type.parse(id("x"), definition("scope", "world", "type", "number"), templates)).isEmpty();
            assertThat(type.parse(id("x"), definition("scope", "player", "type", "int"), templates)).isEmpty();
            assertThat(type.parse(id("x"), definition("type", "number"), templates)).isEmpty();
        }

        @Test
        void rejectsADefaultOfTheWrongType() {
            assertThat(type.parse(id("coins"),
                    definition("scope", "player", "type", "number", "default", "lots"), templates)).isEmpty();
            assertThat(type.parse(id("claimed"),
                    definition("scope", "player", "type", "boolean", "default", "yes"), templates)).isEmpty();
        }

        @Test
        void unknownKeysAreIgnoredAndTheDefinitionIsUntouched() {
            var raw = definition("scope", "player", "type", "number", "x-note", "kept");
            var parsed = type.parse(id("coins"), raw, templates);

            assertThat(parsed).isPresent();
            assertThat(raw).containsEntry("x-note", "kept");
        }

        @Test
        void compileProducesNoPackOutput() {
            // the declaration is runtime state; the type emits nothing into the pack
            type.compile(new Object(), id("coins"), null, null);
        }
    }
}
