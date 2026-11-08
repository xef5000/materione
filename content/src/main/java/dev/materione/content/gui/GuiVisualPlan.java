package dev.materione.content.gui;

import dev.materione.api.gui.TitleSink;
import java.util.List;
import java.util.Set;

/**
 * Ordered title pieces retained for per-viewer assembly (gui.md §4, step 7). The static background is not part of
 * a plan: a GUI with dynamic visuals paints none (§4 step 7), so its title starts empty.
 */
public record GuiVisualPlan(String namespace, List<Piece> pieces) {
    public GuiVisualPlan {
        pieces = List.copyOf(pieces);
    }

    public sealed interface Piece permits Raster, Text {
        String layer();
        int order();
        Object visibleIf();
    }

    /**
     * One frame of a raster layer: its art tiles in row-major order (none when the frame paints no pixel, in
     * which case it adds nothing to the title) and the container slots it covers.
     */
    public record Glyph(List<GuiTitle.ArtGlyph> tiles, Set<Integer> slots) {
        public Glyph {
            tiles = List.copyOf(tiles);
            slots = Set.copyOf(slots);
        }
    }

    public record Raster(String layer, int order, Object visibleIf, List<Glyph> frames, int every) implements Piece {
        public Raster {
            frames = List.copyOf(frames);
        }

        public Glyph frame(long ticks) {
            return frames.get(every == 0 ? 0 : (int) ((ticks / every) % frames.size()));
        }
    }

    public record Text(String layer, int order, Object visibleIf, int x, int y, String text,
            TitleSink.Font font, int argb) implements Piece {}
}
