package dev.materione.integrations.variables;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.logic.provider.EconomyResult;
import dev.materione.api.logic.variable.GuiSessionPort;
import dev.materione.api.logic.variable.VariableDeclaration;
import dev.materione.api.logic.variable.VariableScope;
import dev.materione.api.logic.variable.VariablesApi;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The text of the {@code %materione_…%} placeholders (logic.md §3.3), free of PlaceholderAPI so the
 * grammar is a unit test. The plugin's PlaceholderAPI expansion calls {@link #resolve} for each
 * request and passes the viewer and a name lookup.
 *
 * <pre>
 *   %materione_var_&lt;pack&gt;_&lt;key&gt;%             the viewer's value of a declared variable
 *   %materione_var_&lt;pack&gt;_&lt;key&gt;_&lt;player&gt;%  the named player's value (player scope)
 *   %materione_gui_open%                    the GUI the viewer has open, or empty
 *   %materione_gui_last%                    the GUI before it, or empty
 * </pre>
 *
 * <p>Declared ids decide the split: {@code <pack>_<key>} may contain underscores on either side, so
 * the longest declared prefix wins and the rest is the player name. An undeclared name, and any
 * identifier that is not one of these, resolves to null, which leaves the token as PlaceholderAPI
 * found it.
 *
 * <p>When a currency bridge is supplied, balance tokens match complete encoded declared ids, not an
 * inferred underscore split. Collisions, including a raw id ending in {@code _formatted}, are unresolved.
 */
public final class VariablePlaceholders {

    private static final String VAR = "var_";
    private static final String BALANCE = "balance_";
    private static final long REPORT_INTERVAL_MS = 1000;

    private final VariablesApi variables;
    private final Supplier<GuiSessionPort> guiSessions;
    private final CurrencyPlaceholderBridge currencies;
    private final Consumer<Diagnostic> diagnostics;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, Long> lastReport = new ConcurrentHashMap<>();

    /**
     * @param variables the declarations and values, read per request
     * @param guiSessions the GUI session port, read per request (it is registered when GUI sessions exist)
     */
    public VariablePlaceholders(VariablesApi variables, Supplier<GuiSessionPort> guiSessions) {
        this(variables, guiSessions, null, diagnostic -> { }, System::currentTimeMillis);
    }

    /** Enables balance placeholders through a live, thread-safe bridge and diagnostic sink. */
    public VariablePlaceholders(VariablesApi variables, Supplier<GuiSessionPort> guiSessions,
            CurrencyPlaceholderBridge currencies, Consumer<Diagnostic> diagnostics) {
        this(variables, guiSessions, Objects.requireNonNull(currencies, "currencies"),
                diagnostics, System::currentTimeMillis);
    }

    VariablePlaceholders(VariablesApi variables, Supplier<GuiSessionPort> guiSessions,
            CurrencyPlaceholderBridge currencies, Consumer<Diagnostic> diagnostics, LongSupplier clock) {
        this.variables = Objects.requireNonNull(variables, "variables");
        this.guiSessions = Objects.requireNonNull(guiSessions, "guiSessions");
        this.currencies = currencies;
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * The text of one placeholder.
     *
     * @param identifier the part after {@code materione_}, for example {@code var_shop_coins}
     * @param viewer the player the placeholder is expanded for, or null for none (console)
     * @param playerNamed resolves a player name to its id (online or known), empty when unknown
     * @return the text, or null when the identifier is not a Materione placeholder or names nothing declared
     */
    public String resolve(String identifier, UUID viewer, Function<String, Optional<UUID>> playerNamed) {
        if (identifier == null) {
            return null;
        }
        return switch (identifier) {
            case "gui_open" -> guiSessions.get().openGui(viewer).orElse("");
            case "gui_last" -> guiSessions.get().lastGui(viewer).orElse("");
            default -> identifier.startsWith(VAR) ? variable(identifier.substring(VAR.length()), viewer, playerNamed)
                    : identifier.startsWith(BALANCE) && currencies != null
                            ? balance(identifier.substring(BALANCE.length()), viewer) : null;
        };
    }

    private record BalanceMatch(NamespacedId id, boolean formatted) { }

    private String balance(String token, UUID viewer) {
        BalanceMatch match = null;
        try {
            for (NamespacedId id : currencies.currencies()) {
                String encoded = id.namespace() + "_" + id.value();
                BalanceMatch candidate = token.equals(encoded) ? new BalanceMatch(id, false)
                        : token.equals(encoded + "_formatted") ? new BalanceMatch(id, true) : null;
                if (candidate != null) {
                    if (match != null && !match.equals(candidate)) {
                        return null;
                    }
                    match = candidate;
                }
            }
        } catch (RuntimeException error) {
            report("catalog", null, "currency catalog failed: " + describe(error));
            return null;
        }
        if (match == null) {
            return null;
        }
        NamespacedId id = match.id();
        if (viewer == null) {
            report(id.toString(), id, "no viewer for balance");
            return "";
        }
        CurrencyPlaceholderBridge.Balance balance;
        try {
            balance = currencies.read(id, viewer, match.formatted());
        } catch (RuntimeException error) {
            report(id.toString(), id, "balance request failed: " + describe(error));
            return "";
        }
        EconomyResult result = balance == null ? null : balance.result();
        if (!(result instanceof EconomyResult.Succeeded succeeded)) {
            String reason = result instanceof EconomyResult.Failed failed ? failed.reason()
                    : "provider returned no balance";
            report(id.toString(), id, reason);
            return "";
        }
        double amount = succeeded.balance();
        if (!Double.isFinite(amount)) {
            report(id.toString(), id, "provider returned a non-finite balance");
            return "";
        }
        if (!match.formatted()) {
            return BigDecimal.valueOf(amount).stripTrailingZeros().toPlainString();
        }
        String formatted = balance.formatted();
        if (formatted == null) {
            report(id.toString(), id, "currency formatting unavailable");
            return "";
        }
        return formatted;
    }

    private static String describe(RuntimeException error) {
        return error.getClass().getSimpleName() + (error.getMessage() == null ? "" : ": " + error.getMessage());
    }

    private void report(String key, NamespacedId id, String problem) {
        long now = clock.getAsLong();
        AtomicBoolean emit = new AtomicBoolean();
        lastReport.compute(key, (ignored, previous) -> {
            if (previous == null || now < previous || now - previous >= REPORT_INTERVAL_MS) {
                emit.set(true);
                return now;
            }
            return previous;
        });
        if (emit.get()) {
            diagnostics.accept(new Diagnostic(DiagnosticCodes.ACTION_FAILED, Severity.WARN,
                    "Currency placeholder " + (id == null ? "catalog" : id) + ": " + problem,
                    id, null, "The placeholder is unavailable; repeated reports are rate-limited."));
        }
    }

    private String variable(String rest, UUID viewer, Function<String, Optional<UUID>> playerNamed) {
        VariableDeclaration match = null;
        String playerName = null;
        int longest = -1;
        for (VariableDeclaration declaration : variables.declarations()) {
            String prefix = declaration.pack() + "_" + declaration.id().value();
            if (rest.equals(prefix) && prefix.length() > longest) {
                match = declaration;
                playerName = null;
                longest = prefix.length();
            } else if (rest.startsWith(prefix + "_") && prefix.length() > longest) {
                match = declaration;
                playerName = rest.substring(prefix.length() + 1);
                longest = prefix.length();
            }
        }
        if (match == null) {
            return null;
        }
        UUID holder = null;
        if (match.scope() == VariableScope.PLAYER) {
            if (playerName != null) {
                Optional<UUID> named = playerNamed.apply(playerName);
                if (named.isEmpty()) {
                    return null; // a name no player has: nothing to show
                }
                holder = named.get();
            } else {
                holder = viewer;
            }
            if (holder == null) {
                return "";
            }
        }
        return variables.get(match.id(), holder).map(VariablePlaceholders::text).orElse("");
    }

    /** A value as placeholder text: whole numbers without a fraction, booleans as true/false. */
    static String text(Object value) {
        if (value instanceof Double number && number == Math.rint(number) && Math.abs(number) < 1e15) {
            return Long.toString(number.longValue());
        }
        return String.valueOf(value);
    }
}
