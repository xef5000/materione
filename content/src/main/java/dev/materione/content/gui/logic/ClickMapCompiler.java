package dev.materione.content.gui.logic;

import dev.materione.api.gui.ClickArea;
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
 *       interior falls inside the rectangle. Slot {@code n} has its item origin at
 *       {@code (8 + 18·col, 18 + 18·row)}, so its centre is the pixel {@code (16 + 18·col,
 *       26 + 18·row)}; the rectangle is the half-open range {@code [x, x + width) x [y, y + height)}.
 *       A rectangle that only grazes a slot's edge does not take it.
 *   <li><b>Slot set</b> (item, slot wells): exactly those container slots, clipped to the GUI's rows.
 * </ul>
 *
 * <p>The order inside a slot is the layer order reversed, so the topmost layer comes first. A layer
 * whose component throws while declaring its area covers nothing; a hidden layer, or one in
 * {@code skipped} (the compiler dropped it), is not considered at all.
 */
public final class ClickMapCompiler {

    /** The x pixel of a container slot's interior centre, by column. */
    static int centreX(int column) {
        return 8 + 18 * column + 8;
    }

    /** The y pixel of a container slot's interior centre, by row. */
    static int centreY(int row) {
        return 18 + 18 * row + 8;
    }

    private ClickMapCompiler() {}

    /**
     * @param gui the parsed GUI
     * @param textures pixels of the owning pack's textures (an unsized image is as big as its texture)
     * @param skipped ids of layers the GUI compile skipped; they take no clicks
     */
    public static ClickMap compile(GuiDefinition gui, TextureSource textures, Collection<String> skipped) {
        int slots = gui.rows() * 9;
        var perSlot = new ArrayList<List<String>>(slots);
        for (int slot = 0; slot < slots; slot++) {
            perSlot.add(new ArrayList<>());
        }
        // bottom to top, then each slot's list is flipped so the topmost layer leads
        for (GuiLayer layer : gui.layers()) {
            if (!layer.visible() || skipped.contains(layer.id())) {
                continue;
            }
            for (int slot : coveredSlots(area(layer, textures), gui.rows())) {
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

    /** The container slots {@code area} covers in a chest of {@code rows} rows, ascending. */
    public static List<Integer> coveredSlots(ClickArea area, int rows) {
        var covered = new ArrayList<Integer>();
        switch (area) {
            case ClickArea.None none -> {
                // nothing to click
            }
            case ClickArea.Slots set -> {
                Set<Integer> wanted = set.slots();
                for (int slot = 0; slot < rows * 9; slot++) {
                    if (wanted.contains(slot)) {
                        covered.add(slot);
                    }
                }
            }
            case ClickArea.Rect rect -> {
                for (int slot = 0; slot < rows * 9; slot++) {
                    int cx = centreX(slot % 9);
                    int cy = centreY(slot / 9);
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
