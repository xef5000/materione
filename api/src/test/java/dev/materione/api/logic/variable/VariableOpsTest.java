package dev.materione.api.logic.variable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.NamespacedId;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class VariableOpsTest {

    @Nested
    class Types {

        @Test
        void aLiteralMustAlreadyHaveTheTypesJavaShape() {
            assertThat(VariableType.NUMBER.accepts(5)).isTrue();
            assertThat(VariableType.NUMBER.accepts("5")).isFalse();
            assertThat(VariableType.NUMBER.accepts(Double.NaN)).isFalse();
            assertThat(VariableType.BOOLEAN.accepts(1)).isFalse();
            assertThat(VariableType.STRING.accepts("x")).isTrue();
        }

        @Test
        void aComputedValueIsCoercedWhenItReadsAsTheType() {
            assertThat(VariableType.NUMBER.coerce("2.5")).contains(2.5d);
            assertThat(VariableType.NUMBER.coerce(3)).contains(3.0d);
            assertThat(VariableType.NUMBER.coerce("abc")).isEmpty();
            assertThat(VariableType.NUMBER.coerce("Infinity")).isEmpty();
            assertThat(VariableType.BOOLEAN.coerce("TRUE")).contains(true);
            assertThat(VariableType.BOOLEAN.coerce("yes")).isEmpty();
            assertThat(VariableType.STRING.coerce(4)).isEmpty();
        }

        @Test
        void defaultsAreZeroEmptyAndFalse() {
            assertThat(VariableType.NUMBER.defaultValue()).isEqualTo(0.0d);
            assertThat(VariableType.STRING.defaultValue()).isEqualTo("");
            assertThat(VariableType.BOOLEAN.defaultValue()).isEqualTo(false);
            assertThat(VariableType.of("Number")).contains(VariableType.NUMBER);
            assertThat(VariableType.of("int")).isEmpty();
        }
    }

    @Nested
    class Operations {

        @Test
        void setReplacesTheValueOrStoresAnyValueForARunLocal() {
            assertThat(VariableOps.apply(VariableOp.SET, VariableType.NUMBER, 1.0, 5.0).value()).isEqualTo(5.0);
            assertThat(VariableOps.apply(VariableOp.SET, null, "old", 7).value()).isEqualTo(7);
        }

        @Test
        void setCoercesToTheDeclaredTypeOrRefuses() {
            assertThat(VariableOps.apply(VariableOp.SET, VariableType.NUMBER, 0.0, "4").value()).isEqualTo(4.0);
            var refused = VariableOps.apply(VariableOp.SET, VariableType.NUMBER, 0.0, "four");
            assertThat(refused.ok()).isFalse();
            assertThat(refused.reason()).contains("not a number");
        }

        @Test
        void arithmeticAddsSubtractsAndMultipliesNumbers() {
            assertThat(VariableOps.apply(VariableOp.ADD, VariableType.NUMBER, 10.0, 5).value()).isEqualTo(15.0);
            assertThat(VariableOps.apply(VariableOp.SUBTRACT, VariableType.NUMBER, 10.0, "4").value())
                    .isEqualTo(6.0);
            assertThat(VariableOps.apply(VariableOp.MULTIPLY, VariableType.NUMBER, 3.0, 2.5).value())
                    .isEqualTo(7.5);
        }

        @Test
        void arithmeticOnAnUnsetLocalStartsFromZero() {
            assertThat(VariableOps.apply(VariableOp.ADD, null, null, 3).value()).isEqualTo(3.0);
        }

        @Test
        void arithmeticOnANonNumberFailsWithAReason() {
            var held = VariableOps.apply(VariableOp.ADD, null, "text", 1);
            assertThat(held.ok()).isFalse();
            assertThat(held.reason()).contains("not a number");

            var operand = VariableOps.apply(VariableOp.SUBTRACT, VariableType.NUMBER, 1.0, "two");
            assertThat(operand.ok()).isFalse();

            var missing = VariableOps.apply(VariableOp.MULTIPLY, VariableType.NUMBER, 1.0, null);
            assertThat(missing.ok()).isFalse();
            assertThat(missing.reason()).contains("needs a value");
        }

        @Test
        void arithmeticThatOverflowsFails() {
            var overflow = VariableOps.apply(VariableOp.MULTIPLY, VariableType.NUMBER, Double.MAX_VALUE, 2);
            assertThat(overflow.ok()).isFalse();
            assertThat(overflow.reason()).contains("finite");
        }

        @Test
        void toggleNegatesABooleanAndTreatsAnUnsetOneAsFalse() {
            assertThat(VariableOps.apply(VariableOp.TOGGLE, VariableType.BOOLEAN, true, null).value())
                    .isEqualTo(false);
            assertThat(VariableOps.apply(VariableOp.TOGGLE, null, null, null).value()).isEqualTo(true);
            var refused = VariableOps.apply(VariableOp.TOGGLE, VariableType.NUMBER, 2.0, null);
            assertThat(refused.ok()).isFalse();
        }

        @Test
        void removeAndResetClearTheValue() {
            var removed = VariableOps.apply(VariableOp.REMOVE, VariableType.NUMBER, 4.0, null);
            assertThat(removed.ok()).isTrue();
            assertThat(removed.value()).isNull();
            assertThat(VariableOps.apply(VariableOp.RESET, VariableType.STRING, "x", null).value()).isNull();
        }
    }

    @Nested
    class Parsing {

        @Test
        void opsAreParsedByTheirAuthoredWord() {
            assertThat(VariableOp.of("Add")).contains(VariableOp.ADD);
            assertThat(VariableOp.of("nope")).isEmpty();
            assertThat(VariableOp.TOGGLE.takesValue()).isFalse();
            assertThat(VariableOp.SET.takesValue()).isTrue();
        }

        @Test
        void scopesAreParsedByTheirAuthoredWord() {
            assertThat(VariableScope.of("server")).contains(VariableScope.SERVER);
            assertThat(VariableScope.of("session")).isEqualTo(Optional.empty());
        }
    }

    @Nested
    class Declarations {

        @Test
        void aDeclarationWithoutADefaultHoldsTheTypesDefault() {
            var declaration = new VariableDeclaration(
                    NamespacedId.of("shop", "coins"), VariableScope.PLAYER, VariableType.NUMBER, null, null);
            assertThat(declaration.defaultValue()).isEqualTo(0.0d);
            assertThat(declaration.description()).isEmpty();
            assertThat(declaration.pack()).isEqualTo("shop");
        }

        @Test
        void aDefaultThatDoesNotFitTheTypeIsRefused() {
            assertThatThrownBy(() -> new VariableDeclaration(
                    NamespacedId.of("shop", "coins"), VariableScope.PLAYER, VariableType.NUMBER, "lots", null))
                    .hasMessageContaining("does not fit type number");
        }
    }
}
