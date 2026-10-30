package dev.materione.plugin.logic.support;

import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The one item matcher (logic.md §17 decision 2, §22): which stacks an item reference names, and what
 * text they must carry. {@code take_item} and {@code has_item} both build it, so they agree on what an
 * item is.
 *
 * <p>The defaults are the take behaviour: text is matched ignoring case, and a vanilla reference matches
 * only stacks with no Materione identity ({@code strict}). {@link #of} builds exactly that, so
 * {@code take_item} is unchanged.
 *
 * @param ref the item the author named
 * @param name a display-name text the stack's name must contain, when given
 * @param lore a text some lore line must contain, when given
 * @param ignoreCase whether the texts compare without case (by {@link Locale#ROOT})
 * @param strict whether a vanilla reference excludes stacks that carry a Materione identity; a Materione
 *        reference is unaffected
 */
public record ItemMatch(ItemRef ref, Optional<String> name, Optional<String> lore, boolean ignoreCase,
        boolean strict) {

    public ItemMatch {
        name = name == null ? Optional.empty() : name;
        lore = lore == null ? Optional.empty() : lore;
    }

    /** The take behaviour: the name text (ignoring case), strict vanilla matching, no lore. */
    public static ItemMatch of(ItemRef ref, Optional<String> nameContains) {
        return new ItemMatch(ref, nameContains, Optional.empty(), true, true);
    }

    /** The predicate over stack views this match describes. */
    public Predicate<ItemView> predicate() {
        String nameNeedle = name.map(this::fold).orElse(null);
        String loreNeedle = lore.map(this::fold).orElse(null);
        return view -> ref.matches(view, strict)
                && (nameNeedle == null || view.name().map(text -> fold(text).contains(nameNeedle)).orElse(false))
                && (loreNeedle == null || view.lore().stream().anyMatch(line -> fold(line).contains(loreNeedle)));
    }

    private String fold(String text) {
        return ignoreCase ? text.toLowerCase(Locale.ROOT) : text;
    }
}
