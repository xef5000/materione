package dev.materione.integrations.variables;

import dev.materione.api.logic.variable.GuiSessionPort;
import dev.materione.api.logic.variable.VariableDeclaration;
import dev.materione.api.logic.variable.VariableScope;
import dev.materione.api.logic.variable.VariablesApi;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
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
 * <p><b>Reserved seam.</b> {@code %materione_balance_<pack>_<id>%} and {@code …_formatted} belong to the
 * currencies of roadmap #233 and are not implemented here: they resolve to null until that PR adds them
 * beside the {@code var_} branch.
 */
public final class VariablePlaceholders {

    private static final String VAR = "var_";

    private final VariablesApi variables;
    private final Supplier<GuiSessionPort> guiSessions;

    /**
     * @param variables the declarations and values, read per request
     * @param guiSessions the GUI session port, read per request (it is registered when GUI sessions exist)
     */
    public VariablePlaceholders(VariablesApi variables, Supplier<GuiSessionPort> guiSessions) {
        this.variables = variables;
        this.guiSessions = guiSessions;
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
            default -> identifier.startsWith(VAR) ? variable(identifier.substring(VAR.length()), viewer, playerNamed) : null;
        };
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
