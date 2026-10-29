package dev.materione.api.logic;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A palette group for actions and conditions (logic.md §4.1). The node editor colours and groups
 * by category, and the vocabulary lists every category in use. The eight built-ins are constants;
 * an addon may introduce its own with {@link #of}, which the vocabulary then carries as an
 * addon-added category.
 *
 * @param id lowercase slug (the stable key the editor colours by)
 * @param displayName short human name
 */
public record LogicCategory(String id, String displayName) {

    private static final Pattern FORMAT = Pattern.compile("[a-z][a-z0-9_-]*");

    public static final LogicCategory PLAYER = new LogicCategory("player", "Player");
    public static final LogicCategory GUI = new LogicCategory("gui", "GUI");
    public static final LogicCategory WORLD = new LogicCategory("world", "World");
    public static final LogicCategory SERVER = new LogicCategory("server", "Server");
    public static final LogicCategory ECONOMY = new LogicCategory("economy", "Economy");
    public static final LogicCategory DATA = new LogicCategory("data", "Data");
    public static final LogicCategory FLOW = new LogicCategory("flow", "Flow");
    public static final LogicCategory INTEGRATION = new LogicCategory("integration", "Integration");

    private static final List<LogicCategory> BUILT_IN =
            List.of(PLAYER, GUI, WORLD, SERVER, ECONOMY, DATA, FLOW, INTEGRATION);

    public LogicCategory {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        if (!FORMAT.matcher(id).matches()) {
            throw new IllegalArgumentException(
                    "Invalid category id '" + id + "': must match [a-z][a-z0-9_-]*");
        }
    }

    /** An addon-defined category; the display name defaults to the capitalised id. */
    public static LogicCategory of(String id) {
        Objects.requireNonNull(id, "id");
        return new LogicCategory(id, id.isEmpty() ? id : Character.toUpperCase(id.charAt(0)) + id.substring(1));
    }

    /** The eight categories Materione itself ships, in palette order. */
    public static List<LogicCategory> builtIn() {
        return BUILT_IN;
    }

    /** True for one of the eight built-ins (the vocabulary marks the rest as addon-added). */
    public boolean isBuiltIn() {
        return BUILT_IN.contains(this);
    }
}
