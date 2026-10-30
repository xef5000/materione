package dev.materione.plugin.logic.support;

import dev.materione.api.NamespacedId;
import dev.materione.core.runtime.RuntimeRegistries;
import dev.materione.plugin.items.ItemIdResolver;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What an item-valued parameter names: a vanilla item ({@code minecraft:diamond}) or a live
 * Materione item ({@code ruby}, {@code shop:ruby}). Resolution uses the same front door as
 * {@code /materione give}, so a bare name means the same thing in every action.
 *
 * <p>A vanilla reference holds the material's constant name and is turned into a Bukkit
 * {@code Material} only where a server is running, so this type (and its parse-time check) stays
 * usable without one.
 */
public sealed interface ItemRef permits ItemRef.Vanilla, ItemRef.Materione {

    /** The authored vanilla prefix. */
    String VANILLA_PREFIX = "minecraft:";

    /** A vanilla item, by material constant name ({@code DIAMOND}). Matches only stacks with no Materione identity. */
    record Vanilla(String material) implements ItemRef {}

    /** A live Materione item. */
    record Materione(NamespacedId id) implements ItemRef {}

    private static Pattern vanillaName() {
        return Pattern.compile("minecraft:[a-z0-9_]+");
    }

    /**
     * The parse-time check for an authored literal: a {@code minecraft:} name must be well formed.
     * Templates and bare Materione ids are checked when they render, against the live registries.
     *
     * @throws IllegalArgumentException when a literal vanilla name is malformed
     */
    static void checkLiteral(String source) {
        String text = source.trim();
        if (text.contains("${") || text.contains("%") || !text.regionMatches(true, 0, VANILLA_PREFIX, 0,
                VANILLA_PREFIX.length())) {
            return;
        }
        if (!vanillaName().matcher(text.toLowerCase(Locale.ROOT)).matches()) {
            throw new IllegalArgumentException("'" + text + "' is not a vanilla item id");
        }
    }

    /**
     * Resolves rendered text.
     *
     * @param registries the live registries; may be null, which only vanilla references survive
     * @throws IllegalArgumentException with the reason the text names nothing usable
     */
    static ItemRef resolve(String raw, RuntimeRegistries registries) {
        String text = raw.trim();
        if (text.regionMatches(true, 0, VANILLA_PREFIX, 0, VANILLA_PREFIX.length())) {
            if (!vanillaName().matcher(text.toLowerCase(Locale.ROOT)).matches()) {
                throw new IllegalArgumentException("'" + text + "' is not a vanilla item id");
            }
            return new Vanilla(text.substring(VANILLA_PREFIX.length()).toUpperCase(Locale.ROOT));
        }
        if (registries == null) {
            throw new IllegalArgumentException("no content is loaded yet");
        }
        ItemIdResolver.Result result = ItemIdResolver.resolve(text, registries.items());
        return switch (result.status()) {
            case RESOLVED -> new Materione(result.id());
            case AMBIGUOUS -> throw new IllegalArgumentException("'" + text + "' is ambiguous");
            case UNKNOWN -> throw new IllegalArgumentException("no live item matches '" + text + "'");
        };
    }

    /** True when {@code view} is this item. */
    default boolean matches(ItemView view) {
        return switch (this) {
            case Vanilla vanilla -> view.id().isEmpty() && view.material().equals(vanilla.material());
            case Materione item -> view.id().filter(item.id()::equals).isPresent();
        };
    }
}
