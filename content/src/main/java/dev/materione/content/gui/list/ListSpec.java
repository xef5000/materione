package dev.materione.content.gui.list;

import dev.materione.api.NamespacedId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Where a GUI {@code list} gets its entries, as authored (logic.md §4.5, §6.7). Parsed once at load and pure: the
 * registry of list sources is the engine's, so the content side only knows the syntax, as for item sources.
 *
 * <ul>
 *   <li>{@code online_players}: a plain name, the default source's namespace {@code materione}.</li>
 *   <li>{@code {type: pack_items, pack: shop}}: a mapping, whose {@code type} names the source and whose other
 *       keys are its parameters.</li>
 *   <li>{@code {entries: [...]}}: a mapping with {@code entries} and no {@code type}, the static source.</li>
 * </ul>
 *
 * @param source the source's qualified id
 * @param payload the parameters as authored (a mapping, empty for a plain name); a scalar is read by the source's shorthand
 */
public record ListSpec(NamespacedId source, Object payload) {

    /** The static source: entries written in the layer. */
    public static final NamespacedId STATIC = NamespacedId.of("materione", "static");

    public ListSpec {
        Objects.requireNonNull(source, "source");
        payload = payload == null ? Map.of() : payload;
    }

    /**
     * Parses one authored {@code source:} value.
     *
     * @throws IllegalArgumentException with the reason, when the value does not name a list source
     */
    public static ListSpec parse(Object raw) {
        if (raw instanceof String text && !text.isBlank()) {
            return new ListSpec(qualify(text.trim()), Map.of());
        }
        if (raw instanceof Map<?, ?> mapping) {
            var params = new LinkedHashMap<String, Object>();
            mapping.forEach((key, value) -> {
                if (!(key instanceof String name) || name.isBlank()) {
                    throw new IllegalArgumentException("source parameter names are text");
                }
                params.put(name, value);
            });
            if (params.containsKey("type")) {
                if (!(params.remove("type") instanceof String type) || type.isBlank()) {
                    throw new IllegalArgumentException("source type is the name of a list source");
                }
                return new ListSpec(qualify(type.trim()), params);
            }
            if (params.containsKey("entries")) {
                return new ListSpec(STATIC, params);
            }
        }
        throw new IllegalArgumentException(
                "source names a list source ('online_players') or takes its parameters as a mapping "
                        + "({type: pack_items, pack: shop}), or lists static entries ({entries: [...]})");
    }

    private static NamespacedId qualify(String text) {
        try {
            return text.contains(":") ? NamespacedId.parse(text) : NamespacedId.of("materione", text);
        } catch (IllegalArgumentException malformed) {
            throw new IllegalArgumentException("'" + text + "' is not a list source name");
        }
    }
}
