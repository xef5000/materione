package dev.materione.plugin.logic.condition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.DiagnosticCode;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.ParseRejection;
import dev.materione.api.logic.variable.CompileEnvironment;
import dev.materione.api.logic.variable.SessionVariables;
import dev.materione.api.logic.variable.VariableBinding;
import dev.materione.api.logic.variable.VariableDeclaration;
import dev.materione.api.logic.variable.VariableFacets;
import dev.materione.behavior.logic.EngineExpressions;
import dev.materione.behavior.logic.MapLogicContext;
import dev.materione.plugin.logic.variable.VariableTestSupport;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** {@code var}: load-time resolution like {@code set_var}, and the typed compare (logic.md §22). */
class VarConditionTest {

    private final VarCondition condition = new VarCondition();

    private static Map<String, Object> params(Object... keyValues) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    /** A reader for a resource of pack {@code shop} that sees the shop declarations. */
    private static ParamReader reader(Map<String, Object> params) {
        return ParamReader.of(params, EngineExpressions.INSTANCE,
                CompileEnvironment.of("shop", id -> Optional.ofNullable(VariableTestSupport.declared().get(id))));
    }

    private boolean test(MapLogicContext context, Object... keyValues) {
        return condition.test(condition.parse(reader(params(keyValues))), context);
    }

    private DiagnosticCode rejectionOf(Object... keyValues) {
        try {
            condition.parse(reader(params(keyValues)));
        } catch (ParseRejection rejection) {
            return rejection.code();
        }
        throw new AssertionError("expected a rejection");
    }

    /** A binding holding the given stored values. */
    private static VariableBinding stored(Map<NamespacedId, Object> values) {
        return new VariableBinding() {
            @Override
            public Optional<VariableDeclaration> declaration(NamespacedId id) {
                return Optional.ofNullable(VariableTestSupport.declared().get(id));
            }

            @Override
            public Optional<Object> stored(VariableDeclaration declaration) {
                return Optional.ofNullable(values.get(declaration.id()));
            }

            @Override
            public boolean store(VariableDeclaration declaration, Object value) {
                return false;
            }
        };
    }

    @Test
    void aDeclaredNumberComparesAsANumber() {
        var context = new MapLogicContext(Map.of())
                .withVariables(stored(Map.of(VariableTestSupport.COINS, 150.0)));

        assertThat(test(context, "var", "player.coins", "op", ">=", "value", 100)).isTrue();
        assertThat(test(context, "var", "player.coins", "op", "<", "value", 100)).isFalse();
        assertThat(test(context, "var", "player.shop.coins", "value", 150)).isTrue();
        assertThat(test(context, "var", "player.coins", "op", "!=", "value", 150)).isFalse();
    }

    @Test
    void aDeclaredVariableNeverWrittenComparesAsItsDefault() {
        var context = new MapLogicContext(Map.of()).withVariables(stored(Map.of()));

        assertThat(test(context, "var", "player.coins", "value", 0)).isTrue();
        assertThat(test(context, "var", "server.shop.season", "value", "spring")).isTrue();
        assertThat(test(context, "var", "player.claimed", "value", false)).isTrue();
    }

    @Test
    void theValueMayBeATemplate() {
        var context = new MapLogicContext(Map.of("price", 120))
                .withVariables(stored(Map.of(VariableTestSupport.COINS, 150.0)));

        assertThat(test(context, "var", "player.coins", "op", ">=", "value", "${price}")).isTrue();
    }

    @Test
    void runLocalAndSessionVariablesAreReadWhereSetVarWritesThem() {
        var context = new MapLogicContext(Map.of());
        context.locals().put("tries", 3);
        Map<String, Object> store = new HashMap<>(Map.of("tab", "tools"));
        context.with(VariableFacets.SESSION, new SessionVariables() {
            @Override
            public Optional<Object> get(String name) {
                return Optional.ofNullable(store.get(name));
            }

            @Override
            public void put(String name, Object value) {
                store.put(name, value);
            }
        });

        assertThat(test(context, "var", "tries", "op", "<=", "value", 3)).isTrue();
        assertThat(test(context, "var", "session.tab", "value", "tools")).isTrue();
        assertThat(test(context, "var", "session.tab", "op", ">", "value", "a")).isFalse();
    }

    @Test
    void anAbsentVariableOnlySatisfiesNotEqual() {
        var context = new MapLogicContext(Map.of());

        assertThat(test(context, "var", "session.tab", "value", "tools")).isFalse();
        assertThat(test(context, "var", "session.tab", "op", "!=", "value", "tools")).isTrue();
        assertThat(test(context, "var", "missing", "op", ">", "value", 0)).isFalse();
    }

    @Test
    void anUndeclaredOrMisscopedNameIsRejectedAtLoad() {
        assertThat(rejectionOf("var", "player.gems", "value", 1)).isEqualTo(DiagnosticCodes.VARIABLE_UNDECLARED);
        assertThat(rejectionOf("var", "server.shop.coins", "value", 1)).isEqualTo(DiagnosticCodes.VARIABLE_UNDECLARED);
    }

    @Test
    void aLiteralOrOrderingThatDoesNotFitTheTypeIsRejectedAtLoad() {
        assertThat(rejectionOf("var", "player.coins", "value", "150")) // quoted: text, as set_var reads it
                .isEqualTo(DiagnosticCodes.VARIABLE_TYPE_MISMATCH);
        assertThat(rejectionOf("var", "player.coins", "value", "lots"))
                .isEqualTo(DiagnosticCodes.VARIABLE_TYPE_MISMATCH);
        assertThat(rejectionOf("var", "server.shop.season", "op", ">", "value", "winter"))
                .isEqualTo(DiagnosticCodes.VARIABLE_TYPE_MISMATCH);
    }

    @Test
    void aMissingValueOrUnknownOpIsAPlainParseError() {
        assertThatThrownBy(() -> condition.parse(reader(params("var", "tries"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("value");
        assertThatThrownBy(() -> condition.parse(reader(params("var", "tries", "op", "=>", "value", 1))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("op");
    }
}
