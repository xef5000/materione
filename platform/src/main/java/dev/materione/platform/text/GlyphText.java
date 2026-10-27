package dev.materione.platform.text;

import dev.materione.core.runtime.RuntimeEffects;
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
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * Glyph markers (M4, runtime-systems §6). Authored strings go through
 * {@link MaterioneText}, the one text pipeline, which uses the glyph and
 * shift components built here; {@link #resolveComponent} resolves markers
 * inside text players typed themselves (chat, signs, books, anvils). It turns
 * markers into components — glyph tags ({@code <glyph:id>}, alias {@code <g:id>}),
 * emoji placeholders ({@code :name:}) and shift tags ({@code <shift:N>})
 * become styled components; effect spans ({@code <effect:id>…</effect>})
 * color their content with the effect's shader signal; everything else
 * passes through as legacy {@code &}-text exactly like {@link ItemText}.
 *
 * <p>Glyphs are forced white unless the tag says {@code colorable} — the
 * incumbent trick that stops a chat color bleeding into a bitmap glyph.
 * Viewers without the needed permission (or callers without a registry)
 * see the literal text; components carry their own {@code font} style, so
 * resolved text renders correctly in every vanilla surface it reaches.
 *
 * <p>Effect spans color their plain runs with the effect's signal RGB —
 * the color the injected text vertex shader matches on (the Nexo
 * magic-color technique). Glyph codepoints inside a span render
 * unaffected (they force their own color), and clients without the pack
 * see the signal as plain colored text.
 */
public final class GlyphText {

    /** Permission gate for viewer-dependent contexts (chat, signs, books). */
    public interface Permissions {
        Permissions ALLOW_ALL = permission -> true;

        boolean test(String permission);
    }

    private static volatile Supplier<RuntimeGlyphs> source = () -> null;
    private static volatile Supplier<RuntimeEffects> effectSource = () -> null;

    /** Binds the live registry source once at boot; readers swap atomically. */
    public static void register(Supplier<RuntimeGlyphs> glyphSource) {
        source = glyphSource;
    }

    /** Binds the live effect registry source once at boot; readers swap atomically. */
    public static void registerEffects(Supplier<RuntimeEffects> effectsSource) {
        effectSource = effectsSource;
    }

    /** The live registry, or null before the first apply. */
    public static RuntimeGlyphs glyphs() {
        return source.get();
    }

    /** The live effect registry, or null before the first apply. */
    public static RuntimeEffects effects() {
        return effectSource.get();
    }

    private GlyphText() {}

    /** Resolves author-intent text (item names, lore) — no permission gate; see {@link MaterioneText}. */
    public static Component resolve(String text) {
        return MaterioneText.parse(text);
    }

    /** Resolves viewer-dependent text; denied glyphs stay literal. */
    public static Component resolve(String text, RuntimeGlyphs glyphs, Permissions permissions) {
        return MaterioneText.parse(text, glyphs, effects(), permissions);
    }

    /** Resolves viewer-dependent text against explicit registries (tests, editor previews). */
    public static Component resolve(
            String text, RuntimeGlyphs glyphs, RuntimeEffects effects, Permissions permissions) {
        return MaterioneText.parse(text, glyphs, effects, permissions);
    }

    /**
     * Resolves markers inside an existing component tree (an anvil rename, a
     * chat message): every literal text node is re-resolved in place, styles
     * and children preserved.
     */
    public static Component resolveComponent(Component component, RuntimeGlyphs glyphs, Permissions permissions) {
        RuntimeEffects effects = effects();
        if ((glyphs == null || glyphs.isEmpty()) && (effects == null || effects.isEmpty())
                || component == null) {
            return component;
        }
        var resolved = new ArrayList<Component>();
        boolean changed = false;
        if (component instanceof TextComponent text && !text.content().isEmpty()) {
            String content = text.content();
            if (containsMarker(content)) {
                var replacement = Component.text().style(text.style());
                appendResolved(replacement, content, glyphs, effects, permissions);
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
            TextComponent.Builder builder, String text, RuntimeGlyphs glyphs,
            RuntimeEffects effects, Permissions permissions) {
        boolean noGlyphs = glyphs == null || glyphs.isEmpty();
        boolean noEffects = effects == null || effects.isEmpty();
        if ((noGlyphs && noEffects) || text == null || text.isEmpty()) {
            builder.append(ItemText.legacy(text == null ? "" : text));
            return;
        }
        int at = 0;
        while (at < text.length()) {
            char c = text.charAt(at);
            if (c == '<') {
                int close = text.indexOf('>', at);
                if (close > at + 1) {
                    String tag = text.substring(at + 1, close);
                    // paired effect span: <effect:pack:id>…</effect> — the
                    // span's plain runs take the effect's shader signal color
                    if (isEffectTag(tag) && !noEffects) {
                        int closeStart = effectSpanEnd(text, close + 1, tag);
                        if (closeStart > 0) {
                            String closeTag = effectCloseTag(tag);
                            builder.append(effectSpan(tag,
                                    text.substring(close + 1, closeStart),
                                    glyphs, effects, permissions));
                            at = closeStart + closeTag.length();
                            continue;
                        }
                    }
                    Component resolved = tagComponent(tag, glyphs, permissions);
                    if (resolved != null) {
                        builder.append(resolved);
                        at = close + 1;
                        continue;
                    }
                }
            } else if (c == ':') {
                int closeColon = text.indexOf(':', at + 1);
                if (closeColon > at + 1) {
                    String placeholder = text.substring(at, closeColon + 1);
                    RuntimeGlyphs.Entry entry = glyphs.byPlaceholder(placeholder).orElse(null);
                    Component emoji = emojiComponent(entry, glyphs, permissions);
                    if (emoji != null) {
                        builder.append(emoji);
                        at = closeColon + 1;
                        continue;
                    }
                }
            }
            int next = nextMarker(text, at + 1);
            int plain = appendPlainPlaceholder(builder, text, at, next, glyphs, permissions);
            if (plain < 0) {
                builder.append(ItemText.legacy(text.substring(at, next)));
                at = next;
            } else {
                at = plain; // a plain placeholder was emitted; resume inside the run
            }
        }
    }

    /**
     * Emits the run up to the earliest colon-less emoji placeholder and
     * resolves it (#79); returns the scan resume point, or -1 when the run
     * holds none. Longest wins at a position tie (the registry sorts them
     * first); a denied or unresolvable match emits literally and is skipped —
     * no loop, no codepoint leak.
     */
    private static int appendPlainPlaceholder(
            TextComponent.Builder builder, String text, int from, int to,
            RuntimeGlyphs glyphs, Permissions permissions) {
        var candidates = glyphs.plainPlaceholders();
        int best = -1;
        String match = null;
        for (String candidate : candidates) {
            int hit = text.indexOf(candidate, from);
            if (hit < 0 || hit >= to) {
                continue;
            }
            if (best < 0 || hit < best) { // longest-first order settles ties
                best = hit;
                match = candidate;
            }
        }
        if (match == null) {
            return -1;
        }
        if (best > from) {
            builder.append(ItemText.legacy(text.substring(from, best)));
        }
        Component emoji = emojiComponent(glyphs.byPlaceholder(match).orElse(null), glyphs, permissions);
        builder.append(emoji != null ? emoji : ItemText.legacy(match));
        return best + match.length();
    }

    /** True for {@code effect:…} / {@code e:…} — the paired-span open tag. */
    private static boolean isEffectTag(String tag) {
        return tag.startsWith("effect:") || tag.startsWith("e:");
    }

    /** The close tag matching an effect open tag. */
    private static String effectCloseTag(String tag) {
        return "</" + (tag.startsWith("effect:") ? "effect" : "e") + ">";
    }

    /**
     * Finds the matching close tag's start from {@code from}; -1 when the
     * span is unclosed (then the tag stays literal). Spans do not nest.
     */
    private static int effectSpanEnd(String text, int from, String tag) {
        return text.indexOf(effectCloseTag(tag), from);
    }

    /** The span component: inner content resolved, colored with the effect's signal. */
    private static Component effectSpan(
            String tag, String inner, RuntimeGlyphs glyphs, RuntimeEffects effects,
            Permissions permissions) {
        String reference = tag.substring(tag.indexOf(':') + 1);
        RuntimeEffects.Entry entry = effects.byId(reference).orElse(null);
        if (entry == null) {
            // unknown effect: keep the whole span literal
            return ItemText.legacy("<" + tag + ">" + inner + effectCloseTag(tag));
        }
        var resolved = Component.text();
        appendResolved(resolved, inner, glyphs, effects, permissions);
        // children inherit the parent's color (vanilla component semantics):
        // plain runs take the signal; glyphs keep their forced white, explicit
        // &-codes keep theirs
        return Component.text().color(TextColor.color(entry.signalRgb()))
                .append(resolved)
                .build();
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
                return shiftComponent(parts[1]);
            }
            default -> {
                return null;
            }
        }
    }

    /** {@code <shift:N>}'s advance characters; null when N is not an integer. */
    static Component shiftComponent(String raw) {
        Integer offset = parseInt(raw);
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
    static Component glyphComponent(
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
