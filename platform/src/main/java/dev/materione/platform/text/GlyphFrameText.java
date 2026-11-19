package dev.materione.platform.text;

import dev.materione.core.runtime.RuntimeGlyphs;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;

/**
 * The frame-cycling provider for animated glyphs (runtime-systems §6, M6, #9). The text pipeline resolves an
 * animated glyph to its first frame's codepoint (the static fallback every surface without a clock keeps); a
 * surface that has a clock calls {@link #advance} to rewrite those codepoints to the frame the clock selects.
 *
 * <p>Rewriting is by codepoint, not by tag, so it works on any component however it was authored — a lore
 * line, a display name, a title, a HUD element's text — and keeps every style (color, font, shadow, effect
 * signal) untouched. Only an animated glyph's <em>first</em> frame codepoint moves: a reference pinned to a later
 * frame with {@code index:} stays pinned, and still glyphs and sheets are never touched. The result is built for
 * one viewer's packet and is never stored back into content (D1: files are the source of truth).
 */
public final class GlyphFrameText {

    private GlyphFrameText() {}

    /** True when {@code component} (or a child) carries an animated glyph's first frame. */
    public static boolean containsAnimation(Component component, RuntimeGlyphs glyphs) {
        if (component == null || glyphs == null || !glyphs.hasAnimations()) {
            return false;
        }
        if (component instanceof TextComponent text && containsAnimation(text.content(), glyphs)) {
            return true;
        }
        for (Component child : component.children()) {
            if (containsAnimation(child, glyphs)) {
                return true;
            }
        }
        return false;
    }

    /** True when any component of {@code lines} carries an animated glyph. */
    public static boolean containsAnimation(List<Component> lines, RuntimeGlyphs glyphs) {
        if (lines == null || glyphs == null || !glyphs.hasAnimations()) {
            return false;
        }
        for (Component line : lines) {
            if (containsAnimation(line, glyphs)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The component with every animated glyph showing the frame {@code tick} selects. Returns the same instance
     * when nothing animates, so a caller can cheaply tell that nothing changed.
     */
    public static Component advance(Component component, RuntimeGlyphs glyphs, long tick) {
        if (component == null || glyphs == null || !glyphs.hasAnimations()) {
            return component;
        }
        Component self = component;
        if (component instanceof TextComponent text) {
            String content = advance(text.content(), glyphs, tick);
            if (!content.equals(text.content())) {
                self = text.content(content);
            }
        }
        List<Component> children = component.children();
        if (children.isEmpty()) {
            return self;
        }
        var advanced = new ArrayList<Component>(children.size());
        boolean changed = false;
        for (Component child : children) {
            Component moved = advance(child, glyphs, tick);
            changed |= moved != child;
            advanced.add(moved);
        }
        return changed ? self.children(advanced) : self;
    }

    /** {@link #advance(Component, RuntimeGlyphs, long)} over every line. */
    public static List<Component> advance(List<Component> lines, RuntimeGlyphs glyphs, long tick) {
        if (lines == null || glyphs == null || !glyphs.hasAnimations()) {
            return lines;
        }
        var out = new ArrayList<Component>(lines.size());
        for (Component line : lines) {
            out.add(advance(line, glyphs, tick));
        }
        return out;
    }

    private static boolean containsAnimation(String content, RuntimeGlyphs glyphs) {
        for (int i = 0; i < content.length(); i++) {
            if (glyphs.animationStartingAt(content.charAt(i)).isPresent()) {
                return true;
            }
        }
        return false;
    }

    private static String advance(String content, RuntimeGlyphs glyphs, long tick) {
        StringBuilder out = null;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            RuntimeGlyphs.Entry animation = glyphs.animationStartingAt(c).orElse(null);
            if (animation == null) {
                if (out != null) {
                    out.append(c);
                }
                continue;
            }
            if (out == null) {
                out = new StringBuilder(content.length()).append(content, 0, i);
            }
            out.append((char) animation.frameAt(tick));
        }
        return out == null ? content : out.toString();
    }
}
