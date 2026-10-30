package dev.materione.api.logic.variable;

import dev.materione.api.NamespacedId;
import java.util.Optional;
import java.util.function.Function;

/**
 * What a step needs to know about the resource it was written in while it is parsed (logic.md §3.2):
 * the resource's own pack, so a bare scoped name ({@code player.coins}) resolves there, and the
 * declared variables, so {@code set_var} can reject an undeclared or mistyped write at load.
 *
 * <p>The engine supplies it through {@link dev.materione.api.logic.ParamReader}. A reader built
 * without one sees no pack and no declarations.
 */
public interface CompileEnvironment {

    /** No pack, no declarations: a unit test's reader, or a step with nothing to check against. */
    CompileEnvironment NONE = new CompileEnvironment() {
        @Override
        public String pack() {
            return null;
        }

        @Override
        public Optional<VariableDeclaration> variable(NamespacedId id) {
            return Optional.empty();
        }
    };

    /** The namespace of the resource the step belongs to, or null. */
    String pack();

    /** The declaration of {@code id}, or empty when it is not declared. */
    Optional<VariableDeclaration> variable(NamespacedId id);

    /** An environment for {@code pack} that resolves declarations through {@code declared}. */
    static CompileEnvironment of(String pack, Function<NamespacedId, Optional<VariableDeclaration>> declared) {
        return new CompileEnvironment() {
            @Override
            public String pack() {
                return pack;
            }

            @Override
            public Optional<VariableDeclaration> variable(NamespacedId id) {
                return declared.apply(id);
            }
        };
    }
}
