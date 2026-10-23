package dev.materione.core.runtime;

import dev.materione.api.NamespacedId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The glyph registry (M4, runtime-systems §6): every live glyph with the
 * codepoints the compiler allocated for it, resolved placeholders and emoji
 * metadata. Built into the shadow during the pipeline run and swapped
 * atomically — the runtime text pipeline reads it lock-free per message.
 */
public final class RuntimeGlyphs {

    public static final Class<RuntimeGlyphs> KEY = RuntimeGlyphs.class;

    /** One live glyph. */
    public record Entry(
            NamespacedId id,
            String fontKey,
            int[] codepoints,
            List<String> placeholders,
            String permission,
            boolean emoji,
            boolean tabComplete,
            String shadowColor,
            String referenceTarget,
            String indexSpec) {

        public boolean isReference() {
            return codepoints.length == 0;
        }
    }

    private final Map<NamespacedId, Entry> glyphs;
    private final List<Entry> emoji;
    private final List<String> plainPlaceholders;

    public RuntimeGlyphs(Map<NamespacedId, Entry> glyphs) {
        // insertion order is part of the contract: same content → same registry
        // iteration → deterministic resolution when a bare id is ambiguous
        this.glyphs = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(glyphs));
        this.emoji = this.glyphs.values().stream().filter(Entry::emoji).toList();
        // colon-less emoji placeholders (<3, :D) resolve as exact substrings of
        // literal runs (#79); longest first so <33 wins over <3 at a tie, with
        // registry order breaking exact ties
        this.plainPlaceholders = this.emoji.stream()
                .flatMap(entry -> entry.placeholders().stream())
                .filter(placeholder -> !resolvesByColonBranch(placeholder))
                .distinct()
                .sorted((a, b) -> b.length() - a.length())
                .toList();
    }

    /** True when the colon branch of the scan can match it (":x:" shape). */
    private static boolean resolvesByColonBranch(String placeholder) {
        return placeholder.length() >= 2
                && placeholder.charAt(0) == ':'
                && placeholder.charAt(placeholder.length() - 1) == ':';
    }

    public static RuntimeGlyphs empty() {
        return new RuntimeGlyphs(Map.of());
    }

    /** All glyph entries (insertion order). */
    public java.util.Collection<Entry> all() {
        return glyphs.values();
    }

    public Optional<Entry> glyph(NamespacedId id) {
        return Optional.ofNullable(glyphs.get(id));
    }

    /** Glyph lookup by a bare or full id string ({@code heart} / {@code pack:heart}). */
    public Optional<Entry> byId(String raw) {
        if (raw.contains(":")) {
            return Optional.ofNullable(glyphs.get(NamespacedId.parse(raw)));
        }
        return glyphs.values().stream()
                .filter(entry -> entry.id().value().equals(raw))
                .findFirst();
    }

    /** Glyph whose placeholder matches exactly ({@code :heart:} → the heart). */
    public Optional<Entry> byPlaceholder(String placeholder) {
        return emoji.stream()
                .filter(entry -> entry.placeholders().contains(placeholder))
                .findFirst();
    }

    /** Emoji-mode glyphs, for chat interception and tab-completion lists. */
    public List<Entry> emojiGlyphs() {
        return emoji;
    }

    /**
     * Emoji placeholders the colon branch can never match ({@code <3},
     * {@code :D}) — resolved as exact substrings of literal runs, longest
     * first. Placeholders with no marker character at all still need one to
     * be visited by the scan, so they stay completion-only.
     */
    public List<String> plainPlaceholders() {
        return plainPlaceholders;
    }

    public boolean isEmpty() {
        return glyphs.isEmpty();
    }
}
