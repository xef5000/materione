package dev.materione.plugin.view;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.variable.VariableScope;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Which definitions read which state-key family (views.md section 5.2): a declared variable and its scope, to the
 * definitions that read it and the role each reads it in. It is a pure function of one shadow's definitions, built
 * off-thread by the pipeline and published by the shadow swap with the registries ({@link #KEY}); a reload builds a
 * new one. Immutable, so the write observer reads it from any thread without a lock.
 */
public final class ReadsIndex {

    /** The view key under {@link dev.materione.core.runtime.RuntimeRegistries#view(Class)}. */
    public static final Class<ReadsIndex> KEY = ReadsIndex.class;

    private static final ReadsIndex EMPTY = new ReadsIndex(Map.of());

    /** One definition's read of a family, in one role. */
    public record Reader(ViewRef view, ReadRole role) {

        public Reader {
            Objects.requireNonNull(view, "view");
            Objects.requireNonNull(role, "role");
        }
    }

    private final Map<VariableScope, Map<NamespacedId, Reader[]>> byScope;

    private ReadsIndex(Map<VariableScope, Map<NamespacedId, Reader[]>> byScope) {
        this.byScope = byScope;
    }

    /** The index of a shadow with no readers. */
    public static ReadsIndex empty() {
        return EMPTY;
    }

    /** Builds the index of {@code reads}, each definition by its kind and id. */
    public static ReadsIndex of(Map<ViewRef, ViewReads> reads) {
        var grouped = new EnumMap<VariableScope, Map<NamespacedId, List<Reader>>>(VariableScope.class);
        reads.forEach((view, definition) -> definition.keys().forEach((key, roles) -> {
            for (ReadRole role : roles) {
                grouped.computeIfAbsent(key.scope(), ignored -> new HashMap<>())
                        .computeIfAbsent(key.variable(), ignored -> new ArrayList<>())
                        .add(new Reader(view, role));
            }
        }));
        if (grouped.isEmpty()) {
            return EMPTY;
        }
        var built = new EnumMap<VariableScope, Map<NamespacedId, Reader[]>>(VariableScope.class);
        grouped.forEach((scope, byId) -> {
            var arrays = new HashMap<NamespacedId, Reader[]>();
            byId.forEach((id, readers) -> arrays.put(id, readers.toArray(new Reader[0])));
            built.put(scope, Map.copyOf(arrays));
        });
        return new ReadsIndex(built);
    }

    /**
     * The readers of a family, or null when no definition reads it: the miss that every unread write takes. The array
     * is the index's own and must not be modified.
     */
    public Reader[] readers(VariableScope scope, NamespacedId variable) {
        Map<NamespacedId, Reader[]> byId = byScope.get(scope);
        return byId == null ? null : byId.get(variable);
    }

    /** Every family some definition reads. */
    public int families() {
        return byScope.values().stream().mapToInt(Map::size).sum();
    }
}
