package dev.materione.plugin.logic.condition;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.logic.ConditionDescription;
import dev.materione.api.logic.ConditionType;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.ParseRejection;
import dev.materione.api.logic.TemplateText;
import dev.materione.api.logic.variable.CompileEnvironment;
import dev.materione.api.logic.variable.VariableDeclaration;
import dev.materione.api.logic.variable.VariableFacets;
import dev.materione.api.logic.variable.VariableRef;
import dev.materione.api.logic.variable.VariableType;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * {@code var: {var: player.shop.coins, op: ">=", value: 100}} (logic.md §3, §22): a typed compare on a
 * variable, so a requirement needs no expression. The variable is named as {@code set_var} names it: a
 * run-local, {@code session.<name>}, or a declared {@code player.} or {@code server.} variable. A declared
 * variable is resolved at load: an undeclared name is {@code MTR-0063}, and a literal that does not fit its
 * type, or an ordering on a variable that is not a number, is {@code MTR-0064}. A declared variable never
 * written compares as its default.
 *
 * <p>Two numbers (or numeric text) compare as numbers; anything else compares as text, and only for
 * {@code ==} and {@code !=}: an ordering between values that are not both numbers is false.
 */
public final class VarCondition implements ConditionType<VarCondition.Params> {

    /** The comparison, by the symbol a pack writes. */
    public enum Compare {
        EQ("=="), NE("!="), GT(">"), GE(">="), LT("<"), LE("<=");

        private final String symbol;

        Compare(String symbol) {
            this.symbol = symbol;
        }

        /** The symbol, as written in a file. */
        public String symbol() {
            return symbol;
        }

        /** Whether the comparison orders its operands (and so needs numbers). */
        public boolean orders() {
            return this != EQ && this != NE;
        }

        static Optional<Compare> of(String symbol) {
            return Arrays.stream(values()).filter(compare -> compare.symbol.equals(symbol.trim())).findFirst();
        }
    }

    /** Which variable is read. */
    public sealed interface Source permits LocalSource, SessionSource, DeclaredSource {}

    /** A run-local variable. */
    public record LocalSource(String name) implements Source {}

    /** A variable of the open GUI session. */
    public record SessionSource(String name) implements Source {}

    /** A declared persistent variable, resolved at load. */
    public record DeclaredSource(VariableDeclaration declaration) implements Source {}

    /** The parsed condition. */
    public record Params(Source source, Compare compare, TemplateText value) {}

    private static final List<String> SYMBOLS = Arrays.stream(Compare.values()).map(Compare::symbol).toList();

    @Override
    public String id() {
        return "var";
    }

    @Override
    public ConditionDescription describe() {
        return new ConditionDescription(new SelfDescription("Variable",
                "Compares a run-local, session or declared player or server variable with a value",
                List.of(
                        new ParameterSchema("var", ParameterType.STRING, true, null,
                                "Variable: a local name, session.<name>, player.<key> or server.<pack>.<key>",
                                null, null),
                        new ParameterSchema("op", ParameterType.ENUM, false, "==", "How to compare", SYMBOLS, null),
                        new ParameterSchema("value", ParameterType.STRING, true, null,
                                "Value to compare with, or ${expression}", null, null)),
                "2.0.0"), LogicCategory.DATA, "var");
    }

    @Override
    public Params parse(ParamReader params) {
        Compare compare = params.has("op")
                ? Compare.of(params.text("op")).orElseThrow(() -> new IllegalArgumentException(
                        "op must be one of " + String.join(", ", SYMBOLS)))
                : Compare.EQ;
        if (!params.has("value")) {
            throw new IllegalArgumentException("value is required");
        }
        Source source = sourceOf(params);
        if (source instanceof DeclaredSource declared) {
            check(declared.declaration(), compare, params.raw("value").orElse(null));
        }
        Object raw = params.raw("value").orElseThrow();
        return new Params(source, compare, raw instanceof String ? params.template("value") : literal(raw));
    }

    @Override
    public boolean test(Params params, LogicContext context) {
        Object current = read(params.source(), context);
        String wanted = params.value().render(context);
        return compare(params.compare(), current, wanted);
    }

    // --- load -----------------------------------------------------------------------

    private static Source sourceOf(ParamReader params) {
        CompileEnvironment env = params.environment();
        VariableRef ref;
        try {
            ref = VariableRef.parse(params.text("var"), env.pack());
        } catch (IllegalArgumentException malformed) {
            throw new ParseRejection(DiagnosticCodes.VARIABLE_UNDECLARED, malformed.getMessage());
        }
        return switch (ref) {
            case VariableRef.Local local -> new LocalSource(local.name());
            case VariableRef.Session session -> new SessionSource(session.name());
            case VariableRef.Persistent persistent -> {
                NamespacedId id = persistent.id();
                VariableDeclaration declaration = env.variable(id).orElseThrow(() -> new ParseRejection(
                        DiagnosticCodes.VARIABLE_UNDECLARED,
                        "'" + id + "' is not a declared variable (declare it in a variables file of its pack)"));
                if (persistent.scope() != null && declaration.scope() != persistent.scope()) {
                    throw new ParseRejection(DiagnosticCodes.VARIABLE_UNDECLARED, "'" + id
                            + "' is declared " + declaration.scope().id() + ", not " + persistent.scope().id());
                }
                yield new DeclaredSource(declaration);
            }
        };
    }

    /** An ordering needs a number variable; a literal must fit the declared type. A template is checked when it runs. */
    private static void check(VariableDeclaration declaration, Compare compare, Object literal) {
        VariableType type = declaration.type();
        if (compare.orders() && type != VariableType.NUMBER) {
            throw mismatch(declaration, compare.symbol() + " compares numbers");
        }
        boolean templated = literal instanceof String text && text.contains("${");
        if (literal != null && !templated && !type.accepts(literal)) {
            throw mismatch(declaration, "value '" + literal + "' is not a " + type.id());
        }
    }

    /** A number or boolean written bare: nothing to render, so it compares as its text. */
    private static TemplateText literal(Object value) {
        String text = String.valueOf(value);
        return new TemplateText() {
            @Override
            public String source() {
                return text;
            }

            @Override
            public String render(LogicContext context) {
                return text;
            }
        };
    }

    private static ParseRejection mismatch(VariableDeclaration declaration, String reason) {
        return new ParseRejection(DiagnosticCodes.VARIABLE_TYPE_MISMATCH,
                declaration.id() + " is declared " + declaration.type().id() + ": " + reason);
    }

    // --- run ------------------------------------------------------------------------

    private static Object read(Source source, LogicContext context) {
        return switch (source) {
            case LocalSource local -> context.locals().get(local.name());
            case SessionSource session -> context.facet(VariableFacets.SESSION)
                    .flatMap(variables -> variables.get(session.name()))
                    .orElse(null);
            case DeclaredSource declared -> context.variables().stored(declared.declaration())
                    .orElse(declared.declaration().defaultValue());
        };
    }

    /** Numbers compare as numbers; otherwise only equality, as text. An absent value equals nothing. */
    static boolean compare(Compare compare, Object current, String wanted) {
        if (current == null) {
            return compare == Compare.NE;
        }
        Double left = number(current);
        Double right = number(wanted);
        if (left != null && right != null) {
            int order = Double.compare(left, right);
            return switch (compare) {
                case EQ -> order == 0;
                case NE -> order != 0;
                case GT -> order > 0;
                case GE -> order >= 0;
                case LT -> order < 0;
                case LE -> order <= 0;
            };
        }
        boolean equal = String.valueOf(current).equals(wanted);
        return switch (compare) {
            case EQ -> equal;
            case NE -> !equal;
            default -> false;
        };
    }

    private static Double number(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException notANumber) {
                return null;
            }
        }
        return null;
    }
}
