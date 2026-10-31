package dev.materione.plugin.gui.dynamic;

import dev.materione.api.logic.TemplateText;
import dev.materione.behavior.flow.Condition;
import dev.materione.content.gui.ItemComponent;
import java.util.List;

/**
 * One item layer compiled for the live view (logic.md §6.3): the condition, the source (or the frame
 * sources), and the templated overrides, all parsed once. Built by {@link ItemLayerCompiler}; read by the
 * slot renderer on every render.
 *
 * @param layerId the layer's id
 * @param order the layer's index among the GUI's layers: a higher order is a higher layer
 * @param slot the chest slot
 * @param visibleIf the compiled condition, or null when the layer is always shown
 * @param source the item source, or null for an animation
 * @param frames the frame sources in order, or null for a single item
 * @param frameEvery ticks between two frames, 0 for a single item
 * @param name the display name template, or null
 * @param lore the lore line templates, empty when none
 * @param loreMode how the lore combines with the item's own lore
 * @param amount the amount template (a whole number from 1 to 64 once rendered)
 * @param glint the glint template ({@code true} or {@code false} once rendered), or null
 * @param dynamic whether the layer changes while the window is open (logic.md §6.3)
 */
public record CompiledItemLayer(
        String layerId, int order, int slot, Condition visibleIf, ItemBinding source, List<ItemBinding> frames,
        int frameEvery, TemplateText name, List<TemplateText> lore, ItemComponent.LoreMode loreMode,
        TemplateText amount, TemplateText glint, boolean dynamic) {

    public CompiledItemLayer {
        frames = frames == null ? null : List.copyOf(frames);
        lore = List.copyOf(lore);
    }

    /** The frame showing at {@code ticks} of the window's clock: {@code (ticks / every) mod n}. */
    public ItemBinding sourceAt(long ticks) {
        if (frames == null) {
            return source;
        }
        return frames.get((int) ((ticks / frameEvery) % frames.size()));
    }
}
