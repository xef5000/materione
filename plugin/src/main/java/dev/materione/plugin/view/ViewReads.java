package dev.materione.plugin.view;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * What one view definition reads, extracted from its compiled form (views.md section 5.2).
 *
 * @param keys the declared variables it reads, with the roles each is read in
 * @param polled whether it also reads something no write announces (a world fact, a {@code %token%}, a provider, a
 *     variable the index cannot place), which only the view's clock or {@code view.refresh} brings up to date
 */
public record ViewReads(Map<ReadKey, Set<ReadRole>> keys, boolean polled) {

    public static final ViewReads NONE = new ViewReads(Map.of(), false);

    public ViewReads {
        var copy = new LinkedHashMap<ReadKey, Set<ReadRole>>();
        keys.forEach((key, roles) -> copy.put(key, Set.copyOf(EnumSet.copyOf(roles))));
        keys = Map.copyOf(copy);
    }
}
