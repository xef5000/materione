package dev.materione.content.gui.item;

import dev.materione.api.NamespacedId;
import java.util.Map;
import java.util.Objects;

/**
 * Where a GUI item layer's item comes from, as authored (logic.md §4.4, §6.3). Parsed once at load, pure:
 * the registry of item sources is the engine's, so the content side only knows the syntax.
 *
 * <ul>
 *   <li>{@link Plain}: a plain id, {@code shop:gui/sword} or {@code minecraft:diamond}. The default source
 *       resolves it; a layer whose only item is a plain id is static and resolved at apply time.</li>
 *   <li>{@link Text}: a plain string that is templated, {@code ${entry.item}}, resolved per viewer.</li>
 *   <li>{@link Keyed}: a one-key mapping that names a source, {@code head: {player: "${player.name}"}}.</li>
 * </ul>
 */
public sealed interface ItemSpec permits ItemSpec.Plain, ItemSpec.Text, ItemSpec.Keyed {

    /** The default source's id: a plain string, unqualified, is {@code materione:item}. */
    NamespacedId DEFAULT_SOURCE = NamespacedId.of("materione", "item");

    /** The viewer's own item in an equipment or hand slot. */
    NamespacedId PLAYER_SLOT = NamespacedId.of("materione", "player_slot");

    /** Whether the item depends on the viewer or on a template, so it is re-read on the refresh clock. */
    boolean dynamic();

    /**
     * Parses one authored {@code item:} or {@code frames.items} entry.
     *
     * @throws IllegalArgumentException with the reason, when the value is not an item source at all
     */
    static ItemSpec parse(Object raw) {
        if (raw instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.isEmpty()) {
                throw new IllegalArgumentException("an item is an id, a template or a source, not blank");
            }
            if (GuiTemplates.isTemplated(trimmed)) {
                return new Text(trimmed);
            }
            try {
                return new Plain(trimmed.contains(":")
                        ? NamespacedId.parse(trimmed)
                        : NamespacedId.of("materione", trimmed));
            } catch (IllegalArgumentException malformed) {
                throw new IllegalArgumentException("'" + trimmed + "' is not an item id");
            }
        }
        if (raw instanceof Map<?, ?> mapping && mapping.size() == 1) {
            Map.Entry<?, ?> only = mapping.entrySet().iterator().next();
            if (only.getKey() instanceof String key && !key.isBlank() && only.getValue() != null) {
                try {
                    NamespacedId source = key.contains(":") ? NamespacedId.parse(key) : NamespacedId.of("materione", key);
                    return new Keyed(source, only.getValue());
                } catch (IllegalArgumentException malformed) {
                    throw new IllegalArgumentException("'" + key + "' is not an item source name");
                }
            }
        }
        throw new IllegalArgumentException(
                "item takes an id ('shop:sword'), a template ('${entry.item}') or one source, "
                        + "such as { head: { player: Notch } }");
    }

    /** A plain item id. */
    record Plain(NamespacedId id) implements ItemSpec {

        public Plain {
            Objects.requireNonNull(id, "id");
        }

        @Override
        public boolean dynamic() {
            return false;
        }
    }

    /** A templated id, resolved per viewer through the default source. */
    record Text(String template) implements ItemSpec {

        public Text {
            Objects.requireNonNull(template, "template");
        }

        @Override
        public boolean dynamic() {
            return true;
        }
    }

    /**
     * A named source and its authored payload: a scalar ({@code player_slot: main_hand}) or a mapping
     * ({@code head: {texture: ...}}). The source parses the payload; content only holds it.
     */
    record Keyed(NamespacedId source, Object payload) implements ItemSpec {

        public Keyed {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(payload, "payload");
        }

        @Override
        public boolean dynamic() {
            return PLAYER_SLOT.equals(source) || GuiTemplates.anyTemplated(payload);
        }
    }
}
