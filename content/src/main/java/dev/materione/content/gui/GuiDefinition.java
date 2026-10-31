package dev.materione.content.gui;

import dev.materione.api.NamespacedId;
import dev.materione.content.gui.logic.GuiRules;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A parsed GUI (gui.md §1–§2): a chest design of {@code rows} rows and its ordered
 * layers. A layer that fails to parse is listed in {@link #skipped()} and left out of
 * {@link #layers()}; the GUI itself survives, so one bad layer never drops the screen.
 *
 * @param id the GUI resource id
 * @param title the accessibility and fallback title
 * @param rows chest rows, 1 to 6
 * @param layers accepted layers, bottom to top, including hidden ones
 * @param skipped layers that were quarantined, in authored order
 * @param rules the GUI's {@code behavior:} and {@code flows:} as authored (logic.md §6)
 * @param raw the merged authored mapping, kept for lossless reads
 * @param refresh the {@code refresh:} clock in ticks (logic.md §6.3), 0 when off
 * @param refreshProblem why {@code refresh:} was not read as a clock, or null
 */
public record GuiDefinition(
        NamespacedId id,
        String title,
        int rows,
        List<GuiLayer> layers,
        List<SkippedLayer> skipped,
        GuiRules rules,
        Map<String, Object> raw,
        int refresh,
        String refreshProblem) {

    public GuiDefinition {
        layers = List.copyOf(layers);
        skipped = List.copyOf(skipped);
        rules = rules == null ? GuiRules.EMPTY : rules;
        raw = Collections.unmodifiableMap(new LinkedHashMap<>(raw));
        if (refresh < 0) {
            throw new IllegalArgumentException("refresh is 0 (off) or a whole number of ticks");
        }
    }

    /** A GUI with its clock off and no problem with it. */
    public GuiDefinition(
            NamespacedId id, String title, int rows, List<GuiLayer> layers, List<SkippedLayer> skipped,
            GuiRules rules, Map<String, Object> raw) {
        this(id, title, rows, layers, skipped, rules, raw, 0, null);
    }

    /**
     * One quarantined layer.
     *
     * @param index its position in the authored {@code layers:} list
     * @param layerId its id as authored, or null when it had none
     * @param type its {@code type:} as authored, or null when it had none
     * @param reason why it was skipped, in the author's terms
     */
    public record SkippedLayer(int index, String layerId, String type, String reason) {

        public SkippedLayer {
            reason = reason == null ? "invalid layer" : reason;
        }
    }
}
