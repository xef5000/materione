package dev.materione.plugin.gui.render;

import dev.materione.core.runtime.RuntimeGlyphs;
import dev.materione.platform.text.GlyphFrameText;
import dev.materione.platform.text.GlyphText;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;

/**
 * Marks the appearances whose text carries an animated glyph (runtime-systems §6, M6, #9), so the window plays the
 * animation on the GUI's own refresh clock. A pure decision over the registry and the appearance: it reads the
 * authored name and lore (and a Materione source item's own), resolves them the way the stack factory does, and
 * stamps a {@link GlyphPhase} when a first-frame codepoint is there. {@link AppearanceStacks} applies the phase when
 * the stack is built. A registry with no animation costs one boolean read.
 */
final class AnimatedAppearances {

    private AnimatedAppearances() {}

    /** The appearance with its phase for {@code ticks}, or the same appearance when nothing in it animates. */
    static Appearance at(Appearance appearance, RuntimeGlyphs glyphs, long ticks) {
        if (glyphs == null || !glyphs.hasAnimations() || !animates(appearance, glyphs)) {
            return appearance;
        }
        return appearance.withPhase(new GlyphPhase(ticks, glyphs.frameSignature(ticks)));
    }

    private static boolean animates(Appearance appearance, RuntimeGlyphs glyphs) {
        if (mentions(appearance.name(), glyphs)) {
            return true;
        }
        for (String line : appearance.lore()) {
            if (mentions(line, glyphs)) {
                return true;
            }
        }
        return switch (appearance.source()) {
            case SlotSource.Materione materione -> mentions(materione.item().displayName(), glyphs)
                    || materione.item().lore().stream().anyMatch(line -> mentions(line, glyphs));
            case SlotSource.Stack stack -> stackAnimates(stack, glyphs);
            case SlotSource.Vanilla vanilla -> false;
            case SlotSource.Barrier barrier -> false;
        };
    }

    /** The marker pre-filter: text with no tag or placeholder character cannot resolve to a glyph. */
    private static boolean mentions(String text, RuntimeGlyphs glyphs) {
        if (text == null || (text.indexOf('<') < 0 && text.indexOf(':') < 0)) {
            return false;
        }
        return GlyphFrameText.containsAnimation(
                GlyphText.resolve(text, glyphs, GlyphText.Permissions.ALLOW_ALL), glyphs);
    }

    private static boolean stackAnimates(SlotSource.Stack source, RuntimeGlyphs glyphs) {
        try {
            List<Component> lore = source.stack().lore();
            var lines = new ArrayList<Component>();
            if (lore != null) {
                lines.addAll(lore);
            }
            Component name = source.stack().getItemMeta() == null
                    ? null : source.stack().getItemMeta().displayName();
            if (name != null) {
                lines.add(name);
            }
            return GlyphFrameText.containsAnimation(lines, glyphs);
        } catch (RuntimeException noServer) {
            return false; // a stack that cannot be read here is simply left still
        }
    }
}
