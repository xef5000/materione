package dev.materione.content.gui.logic;

import dev.materione.api.gui.ClickArea;
import dev.materione.api.gui.GuiContainer;
import dev.materione.api.gui.TextureSource;
import dev.materione.content.gui.GuiDefinition;
import dev.materione.content.gui.GuiLayer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Compiles a GUI's {@link ClickMap} (logic.md §6.2). Each visible layer declares a
 * {@link ClickArea}; this class turns it into container slots with one rule per shape:
 *
 * <ul>
 *   <li><b>Rectangle</b> (button, text, image): a slot is covered when the centre of its 16 x 16
 *       interior falls inside the rectangle. Slot {@code n} has its item origin at the profile's
 *       {@code slot(n)} (gui.md §12.5.1), so its centre is that origin plus 8 on both axes; the rectangle is
 *       the half-open range {@code [x, x + width) x [y, y + height)}.
 *       A rectangle that only grazes a slot's edge does not take it.
 *   <li><b>Slot set</b> (item, slot wells): exactly those container slots, clipped to the container's slot count.
 * </ul>
 *
 * <p>The order inside a slot is the layer order reversed, so the topmost layer comes first. A layer
 * whose component throws while declaring its area covers nothing; a hidden layer, or one in
 * {@code skipped} (the compiler dropped it), is not considered at all.
 */
public final class ClickMapCompiler {

    /** The x pixel of a container slot's interior centre: its origin plus the 8 px half-slot (the profile's slot(i)). */
    static int centreX(GuiContainer container, int slot) {
        return container.slotX(slot) + 8;
    }

    /** The y pixel of a container slot's interior centre: its origin plus the 8 px half-slot (the profile's slot(i)). */
    static int centreY(GuiContainer container, int slot) {
        return container.slotY(slot) + 8;
    }

    private ClickMapCompiler() {}

    /**
     * @param gui the parsed GUI
     * @param textures pixels of the owning pack's textures (an unsized image is as big as its texture)
     * @param skipped ids of layers the GUI compile skipped; they take no clicks
     */
    public static ClickMap compile(GuiDefinition gui, TextureSource textures, Collection<String> skipped) {
        int slots = gui.container().slotCount();
        var perSlot = new ArrayList<List<String>>(slots);
        for (int slot = 0; slot < slots; slot++) {
            perSlot.add(new ArrayList<>());
        }
        // bottom to top, then each slot's list is flipped so the topmost layer leads
        for (GuiLayer layer : gui.layers()) {
            if (!layer.visible() || skipped.contains(layer.id())) {
                continue;
            }
            var covered = new java.util.TreeSet<>(coveredSlots(area(layer, textures), gui.container()));
            if (layer.frames() != null) {
                for (var frame : layer.frames().layers()) {
                    try {
                        covered.addAll(coveredSlots(frame.clickArea(textures), gui.container()));
                    } catch (RuntimeException ignored) {
                        // A broken frame cannot receive clicks.
                    }
                }
            }
            if (layer.compiled() instanceof dev.materione.content.gui.TextComponent.Layer text
                    && dev.materione.content.gui.item.GuiTemplates.isTemplated(text.text())) {
                covered.addAll(coveredSlots(new ClickArea.Rect(text.x(), text.y(), 2048,
                        text.font() == dev.materione.api.gui.TitleSink.Font.SMALLCAPS ? 16 : 8), gui.container()));
            }
            for (int slot : covered) {
                perSlot.get(slot).add(layer.id());
            }
        }
        boolean any = false;
        for (List<String> layers : perSlot) {
            java.util.Collections.reverse(layers);
            any |= !layers.isEmpty();
        }
        return any ? new ClickMap(perSlot) : ClickMap.EMPTY;
    }

    /** The container slots {@code area} covers in {@code container}, ascending. */
    public static List<Integer> coveredSlots(ClickArea area, GuiContainer container) {
        var covered = new ArrayList<Integer>();
        switch (area) {
            case ClickArea.None none -> {
                // nothing to click
            }
            case ClickArea.Slots set -> {
                Set<Integer> wanted = set.slots();
                for (int slot = 0; slot < container.slotCount(); slot++) {
                    if (wanted.contains(slot)) {
                        covered.add(slot);
                    }
                }
            }
            case ClickArea.Rect rect -> {
                for (int slot = 0; slot < container.slotCount(); slot++) {
                    int cx = centreX(container, slot);
                    int cy = centreY(container, slot);
                    if (cx >= rect.x() && cx < rect.x() + rect.width()
                            && cy >= rect.y() && cy < rect.y() + rect.height()) {
                        covered.add(slot);
                    }
                }
            }
        }
        return covered;
    }

    private static ClickArea area(GuiLayer layer, TextureSource textures) {
        try {
            ClickArea area = layer.compiled().clickArea(textures);
            return area == null ? ClickArea.NONE : area;
        } catch (RuntimeException broken) {
            // an addon component that cannot say where it is clicked is simply not clickable
            return ClickArea.NONE;
        }
    }
}
