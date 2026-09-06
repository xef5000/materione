package dev.materione.platform.text;

import dev.materione.core.runtime.RuntimeGlyphs;
import dev.materione.core.text.ShiftMath;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * The text pipeline (M4, runtime-systems §6): turns authored strings into
 * components — glyph tags ({@code <glyph:id>}, alias {@code <g:id>}),
 * emoji placeholders ({@code :name:}) and shift tags ({@code <shift:N>})
 * become styled components; everything else passes through as legacy
 * {@code &}-text exactly like {@link ItemText}.
 *
 * <p>Glyphs are forced white unless the tag says {@code colorable} — the
 * incumbent trick that stops a chat color bleeding into a bitmap glyph.
 * Viewers without the needed permission (or callers without a registry)
 * see the literal text; components carry their own {@code font} style, so
 * resolved text renders correctly in every vanilla surface it reaches.
 */
public final class GlyphText {

    /** Permission gate for viewer-dependent contexts (chat, signs, books). */
    public interface Permissions {
        Permissions ALLOW_ALL = permission -> true;

        boolean test(String permission);
    }

    private static volatile Supplier<RuntimeGlyphs> source = () -> null;

    /** Binds the live registry source once at boot; readers swap atomically. */
    public static void register(Supplier<RuntimeGlyphs> glyphSource) {
        source = glyphSource;
    }

    /** The live registry, or null before the first apply. */
    public static RuntimeGlyphs glyphs() {
        return source.get();
    }

    private GlyphText() {}

    /** Resolves author-intent text (item names, lore) — no permission gate. */
    public static Component resolve(String text) {
        return resolve(text, glyphs(), Permissions.ALLOW_ALL);
    }

    /** Resolves viewer-dependent text; denied glyphs stay literal. */
    public static Component resolve(String text, RuntimeGlyphs glyphs, Permissions permissions) {
        var builder = Component.text();
        appendResolved(builder, text, glyphs, permissions);
        return builder.build();
    }

    /**
     * Resolves markers inside an existing component tree (an anvil rename, a
     * chat message): every literal text node is re-resolved in place, styles
     * and children preserved.
     */
    public static Component resolveComponent(Component component, RuntimeGlyphs glyphs, Permissions permissions) {
        if (glyphs == null || glyphs.isEmpty() || component == null) {
            return component;
        }
        var resolved = new ArrayList<Component>();
        boolean changed = false;
        if (component instanceof TextComponent text && !text.content().isEmpty()) {
            String content = text.content();
            if (containsMarker(content)) {
                var replacement = Component.text().style(text.style());
                appendResolved(replacement, content, glyphs, permissions);
                resolved.add(replacement.build());
                changed = true;
            }
        }
        if (!changed) {
            resolved.add(component.children().isEmpty()
                    ? component
                    : component.children(component.children().stream()
                            .map(child -> resolveComponent(child, glyphs, permissions))
                            .toList()));
            return resolved.get(0);
        }
        var builder = Component.text().style(component.style())
                .append(resolved);
        for (Component child : component.children()) {
            builder.append(resolveComponent(child, glyphs, permissions));
        }
        return builder.build();
    }

    // --- core scanner --------------------------------------------------------

    private static void appendResolved(
            TextComponent.Builder builder, String text, RuntimeGlyphs glyphs, Permissions permissions) {
        if (glyphs == null || glyphs.isEmpty() || text == null || text.isEmpty()) {
            builder.append(ItemText.legacy(text == null ? "" : text));
            return;
        }
        int at = 0;
        while (at < text.length()) {
            char c = text.charAt(at);
            if (c == '<') {
                int close = text.indexOf('>', at);
                if (close > at + 1) {
                    Component tag = tagComponent(text.substring(at + 1, close), glyphs, permissions);
                    if (tag != null) {
                        builder.append(tag);
                        at = close + 1;
                        continue;
                    }
                }
            } else if (c == ':') {
                int close = text.indexOf(':', at + 1);
                if (close > at + 1) {
                    String placeholder = text.substring(at, close + 1);
                    RuntimeGlyphs.Entry entry = glyphs.byPlaceholder(placeholder).orElse(null);
                    Component emoji = emojiComponent(entry, glyphs, permissions);
                    if (emoji != null) {
                        builder.append(emoji);
                        at = close + 1;
                        continue;
                    }
                }
            }
            int next = nextMarker(text, at + 1);
            builder.append(ItemText.legacy(text.substring(at, next)));
            at = next;
        }
    }

    private static int nextMarker(String text, int from) {
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '<' || c == ':') {
                return i;
            }
        }
        return text.length();
    }

    private static boolean containsMarker(String text) {
        return text.indexOf('<') >= 0 || text.indexOf(':') >= 0;
    }

    // --- tags ----------------------------------------------------------------

    /** {@code glyph:id[:colorable]} / {@code g:id[:colorable]} / {@code shift:N}; null when not ours. */
    private static Component tagComponent(String tag, RuntimeGlyphs glyphs, Permissions permissions) {
        String[] parts = tag.split(":");
        if (parts.length < 2) {
            return null;
        }
        switch (parts[0]) {
            case "glyph", "g" -> {
                if (parts[1].isBlank()) {
                    return null;
                }
                boolean colorable = parts.length > 2 && parts[2].equalsIgnoreCase("colorable");
                RuntimeGlyphs.Entry entry = glyphs.byId(parts[1]).orElse(null);
                return glyphComponent(entry, glyphs, colorable, permissions);
            }
            case "shift" -> {
                Integer offset = parseInt(parts[1]);
                if (offset == null) {
                    return null;
                }
                var builder = Component.text();
                for (ShiftMath.Shift shift : ShiftMath.decompose(offset)) {
                    // no font style: every materione font carries the shift provider
                    builder.append(Component.text(shift.character()));
                }
                return builder.build();
            }
            default -> {
                return null;
            }
        }
    }

    /** The emoji-mode path: placeholders resolve only for emoji glyphs with permission. */
    private static Component emojiComponent(
            RuntimeGlyphs.Entry entry, RuntimeGlyphs glyphs, Permissions permissions) {
        if (entry == null || !entry.emoji()) {
            return null;
        }
        return glyphComponent(entry, glyphs, false, permissions);
    }

    /**
     * The component for one glyph: its first codepoint (sheets render their
     * top row; full-canvas composition is HUD/menu work, M6). Default-font
     * glyphs stay unstyled — the client renders unstyled text through
     * {@code minecraft:default}, which references the pack's glyph font, so
     * they render in every context regardless of lost styles. Custom-font
     * glyphs carry their font explicitly. Aliases resolve through their
     * target's codepoints.
     */
    private static Component glyphComponent(
            RuntimeGlyphs.Entry entry, RuntimeGlyphs glyphs, boolean colorable, Permissions permissions) {
        if (entry == null) {
            return null;
        }
        if (permissions != null && entry.permission() != null && !permissions.test(entry.permission())) {
            return null; // denied: the literal stays (no unicode leak)
        }
        RuntimeGlyphs.Entry source = entry;
        int[] codepoints = entry.codepoints();
        if (entry.isReference()) {
            source = glyphs.byId(entry.referenceTarget()).orElse(null);
            if (source == null) {
                return null;
            }
            codepoints = select(source.codepoints(), entry.indexSpec());
        }
        if (codepoints.length == 0) {
            return null;
        }
        var out = Component.text(String.valueOf((char) codepoints[0]));
        if (source.fontKey() != null) {
            out = out.font(Key.key(source.fontKey()));
        }
        if (!colorable) {
            out = out.color(NamedTextColor.WHITE);
        }
        if (source.shadowColor() != null) {
            try {
                out = out.shadowColor(ShadowColor.fromHexString(source.shadowColor()));
            } catch (RuntimeException ignored) {
                // a malformed authored color must never break resolution
            }
        }
        return out;
    }

    /** {@code indexSpec} selects reference cells: null = first, {@code 3} = third, {@code 1..4} = range; anything unusable falls back to the first. */
    static int[] select(int[] codepoints, String indexSpec) {
        if (codepoints.length == 0) {
            return codepoints;
        }
        var out = new ArrayList<Integer>();
        int dots = indexSpec == null ? -1 : indexSpec.indexOf("..");
        try {
            if (dots > 0) {
                int start = Integer.parseInt(indexSpec.substring(0, dots));
                String rest = indexSpec.substring(dots + 2);
                int end = rest.isBlank() ? codepoints.length : Integer.parseInt(rest);
                for (int i = Math.max(1, start); i <= Math.min(end, codepoints.length); i++) {
                    out.add(i);
                }
            } else if (indexSpec != null && !indexSpec.isBlank()) {
                int index = Integer.parseInt(indexSpec.trim());
                if (index >= 1 && index <= codepoints.length) {
                    out.add(index);
                }
            }
        } catch (NumberFormatException e) {
            // fall through to the first-cell default
        }
        if (out.isEmpty()) {
            return new int[] {codepoints[0]};
        }
        return out.stream().mapToInt(i -> codepoints[i - 1]).toArray();
    }

    private static Integer parseInt(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
