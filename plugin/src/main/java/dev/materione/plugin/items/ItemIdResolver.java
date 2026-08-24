package dev.materione.plugin.items;

import dev.materione.api.NamespacedId;
import dev.materione.core.runtime.RuntimeItem;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Turns operator-typed item references into live item ids (the
 * {@code /materione give} front door). A namespaced reference must parse
 * AND be live; a bare value matches live items by case-insensitive value
 * across all packs and reports ambiguity instead of guessing.
 */
public final class ItemIdResolver {

    public enum Status { RESOLVED, AMBIGUOUS, UNKNOWN }

    /**
     * {@code id} is meaningful for RESOLVED; {@code candidates} for
     * AMBIGUOUS (what the operator could have meant).
     */
    public record Result(Status status, NamespacedId id, List<NamespacedId> candidates) {

        static Result resolved(NamespacedId id) {
            return new Result(Status.RESOLVED, id, List.of());
        }

        static Result ambiguous(List<NamespacedId> candidates) {
            return new Result(Status.AMBIGUOUS, null, List.copyOf(candidates));
        }

        static Result unknown() {
            return new Result(Status.UNKNOWN, null, List.of());
        }
    }

    private ItemIdResolver() {}

    public static Result resolve(String raw, Collection<RuntimeItem> items) {
        String text = raw.trim();
        if (text.isEmpty()) {
            return Result.unknown();
        }
        if (text.indexOf(':') >= 0) {
            NamespacedId parsed;
            try {
                parsed = NamespacedId.parse(text);
            } catch (IllegalArgumentException e) {
                return Result.unknown();
            }
            return isLive(parsed, items) ? Result.resolved(parsed) : Result.unknown();
        }
        List<NamespacedId> matches = items.stream()
                .filter(item -> item.id().value().equalsIgnoreCase(text))
                .map(RuntimeItem::id)
                .toList();
        if (matches.size() == 1) {
            return Result.resolved(matches.get(0));
        }
        if (matches.size() > 1) {
            return Result.ambiguous(matches);
        }
        // last resort: unique case-insensitive tail match across packs
        String suffix = text.toLowerCase(Locale.ROOT);
        List<NamespacedId> suffixed = items.stream()
                .filter(item -> item.id().value().toLowerCase(Locale.ROOT).endsWith(suffix))
                .map(RuntimeItem::id)
                .toList();
        if (suffixed.size() == 1) {
            return Result.resolved(suffixed.get(0));
        }
        if (suffixed.size() > 1) {
            return Result.ambiguous(suffixed);
        }
        return Result.unknown();
    }

    private static boolean isLive(NamespacedId id, Collection<RuntimeItem> items) {
        for (RuntimeItem item : items) {
            if (item.id().equals(id)) {
                return true;
            }
        }
        return false;
    }
}
