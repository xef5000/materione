package dev.materione.content.gui;

import dev.materione.api.gui.TitleSink;
import java.util.List;
import java.util.Set;

/** Ordered title pieces retained for per-viewer assembly. */
public record GuiVisualPlan(String namespace, int background, int backgroundAdvance, List<Piece> pieces) {
    public GuiVisualPlan {
        pieces = List.copyOf(pieces);
    }

    public sealed interface Piece permits Raster, Text {
        String layer();
        int order();
        Object visibleIf();
    }

    public record Glyph(int codepoint, int advance, Set<Integer> slots) {
        public Glyph {
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
