package dev.materione.platform.text;

import dev.materione.core.runtime.RuntimeEffects;
import dev.materione.core.runtime.RuntimeGlyphs;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.Context;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.ArgumentQueue;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * The one text pipeline (master §5.7, plugin §6.1): an authored string
 * becomes a component. The stored form is a MiniMessage subset; legacy
 * {@code &}/{@code §} codes stay valid input and are bridged into the same
 * tags first. Materione's own tags — {@code <glyph:id>}, {@code <g:id>},
 * {@code <shift:N>}, {@code <effect:pack:id>…</effect>} — resolve inside
 * the same parse, so a gradient can run across a glyph. Emoji placeholders
 * resolve last, inside the parsed text nodes.
 *
 * <p>Fail-soft: an unknown tag, an unknown glyph or effect id and a denied
 * glyph all stay literal text; parsing never throws on content.
 */
public final class MaterioneText {

    /**
     * The closed style tag set. No click, hover, insertion, selector, NBT
     * or translation tags: authored text cannot carry commands or lookups.
     */
    private static final MiniMessage MINI = MiniMessage.builder()
            .tags(TagResolver.resolver(
                    StandardTags.color(),
                    StandardTags.decorations(),
                    StandardTags.gradient(),
                    StandardTags.rainbow(),
                    StandardTags.transition(),
                    StandardTags.reset(),
                    StandardTags.font()))
            .build();

    /** Legacy color codes 0-f by index, as MiniMessage color names. */
    private static final String[] LEGACY_COLORS = {
        "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray",
        "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white",
    };

    private static final Pattern COLON_PLACEHOLDER = Pattern.compile(":[^:\\s<>]+:");

    private static final Set<String> MATERIONE_TAGS = Set.of("glyph", "g", "shift", "effect", "e");

    private MaterioneText() {}

    /** Author-intent text (names, lore, titles) against the live registries; no permission gate. */
    public static Component parse(String text) {
        return parse(text, GlyphText.glyphs(), GlyphText.effects(), GlyphText.Permissions.ALLOW_ALL);
    }

    /** Parses against explicit registries (tests, previews, viewer-gated glyphs); either may be null. */
    public static Component parse(
            String text, RuntimeGlyphs glyphs, RuntimeEffects effects, GlyphText.Permissions permissions) {
        if (text == null || text.isEmpty()) {
            return Component.empty();
        }
        Component parsed = MINI.deserialize(
                legacyToMiniMessage(text), materioneTags(glyphs, effects, permissions));
        return glyphs == null || glyphs.isEmpty() ? parsed : emoji(parsed, glyphs, permissions);
    }

    /**
     * Text compiled once per boot or into files (painting variants): parsed
     * without glyph or effect registries, so the result never depends on live
     * runtime state (D7) and is the same on the datapack and registry paths.
     * Glyph, effect and emoji markers stay literal here.
     */
    public static Component parseData(String text) {
        return parse(text, null, null, GlyphText.Permissions.ALLOW_ALL);
    }

    /**
     * {@link #parseData} as JSON for a data file: a {@code String} when the
     * text has no formatting (so plain titles compile byte-identically to
     * before), else the text component as {@code Map/List/String/Number/Boolean}.
     */
    public static Object componentJson(String text) {
        com.google.gson.JsonElement tree = net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson()
                .serializeToTree(parseData(text == null ? "" : text));
        if (tree.isJsonPrimitive()) {
            return tree.getAsString();
        }
        if (tree.isJsonObject() && tree.getAsJsonObject().size() == 1 && tree.getAsJsonObject().has("text")
                && tree.getAsJsonObject().get("text").isJsonPrimitive()) {
            return tree.getAsJsonObject().get("text").getAsString();
        }
        return jsonValue(tree);
    }

    private static Object jsonValue(com.google.gson.JsonElement element) {
        if (element.isJsonObject()) {
            var map = new java.util.LinkedHashMap<String, Object>();
            for (var entry : element.getAsJsonObject().entrySet()) {
                map.put(entry.getKey(), jsonValue(entry.getValue()));
            }
            return map;
        }
        if (element.isJsonArray()) {
            var list = new ArrayList<Object>();
            for (var entry : element.getAsJsonArray()) {
                list.add(jsonValue(entry));
            }
            return list;
        }
        if (element.isJsonNull()) {
            return null;
        }
        var primitive = element.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return primitive.getAsBoolean();
        }
        if (primitive.isNumber()) {
            return primitive.getAsNumber();
        }
        return primitive.getAsString();
    }

    /**
     * The legacy {@code §} form of a component, for the surfaces that only
     * take a string. Hex colors use the {@code §x} repeated form; a gradient
     * degrades to one color per character.
     */
    public static String legacySection(Component component) {
        return LegacyComponentSerializer.builder()
                .character(LegacyComponentSerializer.SECTION_CHAR)
                .hexColors()
                .useUnusualXRepeatedCharacterHexFormat()
                .build()
                .serialize(component);
    }

    // --- legacy bridge ---------------------------------------------------------

    /**
     * Rewrites {@code &}/{@code §} codes as MiniMessage tags with legacy
     * meaning: a color or {@code r} closes every tag this bridge opened,
     * decorations open inside the current color. Tags the author wrote are
     * never closed by a code. A string without codes comes back unchanged.
     */
    static String legacyToMiniMessage(String text) {
        if (text.indexOf('&') < 0 && text.indexOf(LegacyComponentSerializer.SECTION_CHAR) < 0) {
            return text;
        }
        var out = new StringBuilder(text.length() + 16);
        Deque<String> open = new ArrayDeque<>();
        int at = 0;
        while (at < text.length()) {
            char c = text.charAt(at);
            if ((c != '&' && c != LegacyComponentSerializer.SECTION_CHAR) || at + 1 >= text.length()) {
                out.append(c);
                at++;
                continue;
            }
            String hex = hexAt(text, at, c);
            if (hex != null) {
                closeAll(out, open);
                out.append("<#").append(hex).append('>');
                open.push("#" + hex);
                at += text.charAt(at + 1) == '#' ? 8 : 14;
                continue;
            }
            char code = Character.toLowerCase(text.charAt(at + 1));
            int color = Character.digit(code, 16);
            if (color >= 0) {
                closeAll(out, open);
                out.append('<').append(LEGACY_COLORS[color]).append('>');
                open.push(LEGACY_COLORS[color]);
            } else if (code == 'r') {
                closeAll(out, open);
            } else {
                String decoration = switch (code) {
                    case 'k' -> "obf";
                    case 'l' -> "b";
                    case 'm' -> "st";
                    case 'n' -> "u";
                    case 'o' -> "i";
                    default -> null;
                };
                if (decoration == null) {
                    out.append(c);
                    at++;
                    continue;
                }
                if (!open.contains(decoration)) {
                    out.append('<').append(decoration).append('>');
                    open.push(decoration);
                }
            }
            at += 2;
        }
        return out.toString();
    }

    /** {@code &#rrggbb} or {@code &x&r&r&g&g&b&b} at {@code at}, lowercased; null otherwise. */
    private static String hexAt(String text, int at, char marker) {
        if (text.charAt(at + 1) == '#' && at + 8 <= text.length()) {
            String hex = text.substring(at + 2, at + 8);
            return isHex(hex) ? hex.toLowerCase(java.util.Locale.ROOT) : null;
        }
        if (Character.toLowerCase(text.charAt(at + 1)) != 'x' || at + 14 > text.length()) {
            return null;
        }
        var hex = new StringBuilder(6);
        for (int i = at + 2; i < at + 14; i += 2) {
            if (text.charAt(i) != marker) {
                return null;
            }
            hex.append(text.charAt(i + 1));
        }
        return isHex(hex.toString()) ? hex.toString().toLowerCase(java.util.Locale.ROOT) : null;
    }

    private static boolean isHex(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.digit(value.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private static void closeAll(StringBuilder out, Deque<String> open) {
        while (!open.isEmpty()) {
            out.append("</").append(open.pop()).append('>');
        }
    }

    // --- Materione tags --------------------------------------------------------

    private static TagResolver materioneTags(
            RuntimeGlyphs glyphs, RuntimeEffects effects, GlyphText.Permissions permissions) {
        return new TagResolver() {
            @Override
            public Tag resolve(String name, ArgumentQueue arguments, Context context) {
                // resolvers share one argument queue: never consume another tag's arguments
                if (!has(name)) {
                    return null;
                }
                List<String> args = new ArrayList<>();
                while (arguments.hasNext()) {
                    args.add(arguments.pop().value());
                }
                return switch (name) {
                    case "glyph", "g" -> glyphTag(args, glyphs, permissions);
                    case "shift" -> shiftTag(args);
                    case "effect", "e" -> effectTag(args, effects);
                    default -> null;
                };
            }

            @Override
            public boolean has(String name) {
                return MATERIONE_TAGS.contains(name);
            }
        };
    }

    /** {@code glyph:id[:colorable]}; the id may be pack-qualified. */
    private static Tag glyphTag(List<String> args, RuntimeGlyphs glyphs, GlyphText.Permissions permissions) {
        if (glyphs == null || args.isEmpty()) {
            return null;
        }
        boolean colorable = args.size() > 1 && args.get(args.size() - 1).equalsIgnoreCase("colorable");
        String id = String.join(":", colorable ? args.subList(0, args.size() - 1) : args);
        if (id.isBlank()) {
            return null;
        }
        Component glyph = GlyphText.glyphComponent(glyphs.byId(id).orElse(null), glyphs, colorable, permissions);
        return glyph == null ? null : Tag.selfClosingInserting(glyph);
    }

    private static Tag shiftTag(List<String> args) {
        if (args.size() != 1) {
            return null;
        }
        Component shift = GlyphText.shiftComponent(args.get(0));
        return shift == null ? null : Tag.selfClosingInserting(shift);
    }

    /** {@code effect:pack:id}: the span's text takes the effect's shader signal color. */
    private static Tag effectTag(List<String> args, RuntimeEffects effects) {
        if (effects == null || args.isEmpty()) {
            return null;
        }
        RuntimeEffects.Entry entry = effects.byId(String.join(":", args)).orElse(null);
        return entry == null ? null : Tag.styling(TextColor.color(entry.signalRgb()));
    }

    // --- emoji -----------------------------------------------------------------

    /** Replaces {@code :name:} and colon-less emoji placeholders inside the parsed text nodes. */
    private static Component emoji(Component parsed, RuntimeGlyphs glyphs, GlyphText.Permissions permissions) {
        Component out = parsed.replaceText(TextReplacementConfig.builder()
                .match(COLON_PLACEHOLDER)
                .replacement((match, original) -> emojiOr(match, original, glyphs, permissions))
                .build());
        List<String> plain = glyphs.plainPlaceholders();
        if (!plain.isEmpty()) {
            String alternation = String.join("|", plain.stream().map(Pattern::quote).toList());
            out = out.replaceText(TextReplacementConfig.builder()
                    .match(Pattern.compile(alternation))
                    .replacement((match, original) -> emojiOr(match, original, glyphs, permissions))
                    .build());
        }
        return out;
    }

    private static ComponentLike emojiOr(
            MatchResult match, TextComponent.Builder original, RuntimeGlyphs glyphs,
            GlyphText.Permissions permissions) {
        RuntimeGlyphs.Entry entry = glyphs.byPlaceholder(match.group()).orElse(null);
        if (entry == null || !entry.emoji()) {
            return original;
        }
        Component glyph = GlyphText.glyphComponent(entry, glyphs, false, permissions);
        return glyph == null ? original : glyph;
    }
}
