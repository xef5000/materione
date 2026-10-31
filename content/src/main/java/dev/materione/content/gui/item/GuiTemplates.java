package dev.materione.content.gui.item;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * What makes an authored GUI value depend on the viewer or the moment (logic.md §6.3): an expression
 * {@code ${...}} or a PlaceholderAPI token {@code %name%}. Content only looks for the syntax; the engine
 * compiles and evaluates it at load and at render time. Pure.
 */
public final class GuiTemplates {

    /** A PlaceholderAPI token: {@code %player_name%}, {@code %vault_eco_balance%}. */
    private static final Pattern PLACEHOLDER = Pattern.compile("%[A-Za-z0-9_.:-]+%");

    private GuiTemplates() {}

    /** True when {@code text} contains an expression or a placeholder token. */
    public static boolean isTemplated(String text) {
        return text != null && (text.contains("${") || PLACEHOLDER.matcher(text).find());
    }

    /** True when any string inside an authored value (a scalar, a list or a mapping) is templated. */
    public static boolean anyTemplated(Object value) {
        if (value instanceof String text) {
            return isTemplated(text);
        }
        if (value instanceof Map<?, ?> map) {
            return map.values().stream().anyMatch(GuiTemplates::anyTemplated);
        }
        if (value instanceof List<?> list) {
            return list.stream().anyMatch(GuiTemplates::anyTemplated);
        }
        return false;
    }
}
