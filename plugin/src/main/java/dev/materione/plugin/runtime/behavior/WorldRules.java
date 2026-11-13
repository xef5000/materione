package dev.materione.plugin.runtime.behavior;

import dev.materione.api.NamespacedId;
import dev.materione.behavior.rule.Rule;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The rule documents of the applied shadow, indexed for the world-event triggers (logic.md §37), and which
 * triggers any item's rules answer. A {@link dev.materione.core.runtime.RuntimeRegistries} view, so it swaps
 * atomically with everything else: an event resolves against one consistent snapshot, and a reload needs no
 * listener churn.
 *
 * <p>{@link #subscribes} is the gate that keeps a trigger free when nobody listens. The Bukkit listeners are
 * registered once, whatever the content; each handler's first act is one set lookup here, and it returns
 * before it builds a context, resolves a stack or reads the world when no rule answers the trigger.
 * {@link #itemsAnswer} is the second gate: a stack is read from the player (a persistent-data read) only when
 * some item has a rule on the trigger, not when only rule documents do.
 */
public final class WorldRules {

    /** The registry-view key. */
    public static final Class<WorldRules> KEY = WorldRules.class;

    private static final WorldRules EMPTY = new WorldRules(Map.of(), Set.of());

    private final Map<NamespacedId, List<Rule>> byDocument;
    private final Map<String, List<Rule>> byTrigger;
    private final Set<String> itemTriggers;
    private final Set<String> subscribed;

    private WorldRules(Map<NamespacedId, List<Rule>> byDocument, Set<String> itemTriggers) {
        var documents = new LinkedHashMap<NamespacedId, List<Rule>>();
        var triggers = new LinkedHashMap<String, List<Rule>>();
        var all = new LinkedHashSet<>(itemTriggers);
        byDocument.forEach((id, rules) -> {
            documents.put(id, List.copyOf(rules));
            for (Rule rule : rules) {
                triggers.computeIfAbsent(rule.on().type(), key -> new ArrayList<>()).add(rule);
                all.add(rule.on().type());
            }
        });
        this.byDocument = Map.copyOf(documents);
        var frozen = new LinkedHashMap<String, List<Rule>>();
        triggers.forEach((trigger, rules) -> frozen.put(trigger, List.copyOf(rules)));
        this.byTrigger = Map.copyOf(frozen);
        this.itemTriggers = Set.copyOf(itemTriggers);
        this.subscribed = Set.copyOf(all);
    }

    public static WorldRules empty() {
        return EMPTY;
    }

    /**
     * @param documents the compiled rules of each rule document, in the order the pipeline met them
     * @param itemRules the compiled rules of every item; only the triggers they answer matter here
     */
    public static WorldRules of(
            Map<NamespacedId, List<Rule>> documents, Map<NamespacedId, List<Rule>> itemRules) {
        var items = new LinkedHashSet<String>();
        itemRules.values().forEach(rules -> rules.forEach(rule -> items.add(rule.on().type())));
        return new WorldRules(documents, items);
    }

    /** Whether any rule of a rule document or an item answers {@code trigger}; the one lookup a listener pays when none does. */
    public boolean subscribes(String trigger) {
        return subscribed.contains(trigger);
    }

    /** Whether any item has a rule on {@code trigger}: only then is a held or worn stack worth reading. */
    public boolean itemsAnswer(String trigger) {
        return itemTriggers.contains(trigger);
    }

    /** The rules of rule documents that answer {@code trigger}, in document order; never null. */
    public List<Rule> forTrigger(String trigger) {
        return byTrigger.getOrDefault(trigger, List.of());
    }

    /** The compiled rules of one rule document, or empty for an unknown or rule-less one. */
    public List<Rule> forDocument(NamespacedId id) {
        return byDocument.getOrDefault(id, List.of());
    }

    public int documentCount() {
        return byDocument.size();
    }
}
