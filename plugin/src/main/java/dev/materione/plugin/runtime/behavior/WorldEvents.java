package dev.materione.plugin.runtime.behavior;

import dev.materione.behavior.rule.FilterFacts;
import dev.materione.behavior.rule.Rule;
import dev.materione.behavior.runtime.BehaviorEvent;
import dev.materione.behavior.runtime.RuleEngine;
import dev.materione.core.runtime.RuntimeRegistries;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.inventory.ItemStack;

/**
 * The bridge from a world event to the rules that answer it (logic.md §37): the item's rules first, for
 * each stack the trigger names as a host, then the rule documents'. The listener converts a Bukkit event into
 * one {@link Fire}; this class resolves rules from the live shadow at event entry (a reload mid-event changes
 * nothing for an event already in flight), builds the context tree through {@link BehaviorService} so a rule
 * document sees the same {@code player}, {@code location}, {@code world}, {@code server} and {@code vars} an
 * item rule does, and drives the engine.
 *
 * <p>The work is staged so each step is paid only when the one before it found a reason to continue:
 * {@link #subscribes} (the listener's gate), then the rules whose trigger and filters match this event's facts,
 * then the stacks (only when an item has a rule on the trigger), then the context tree. A server whose only
 * rule is {@code entity.kill} of zombies builds no context for the death of a cow.
 */
public final class WorldEvents {

    private final BehaviorService behavior;
    private final Supplier<RuntimeRegistries> registries;
    private final RuleEngine engine;

    public WorldEvents(BehaviorService behavior, Supplier<RuntimeRegistries> registries, RuleEngine engine) {
        this.behavior = behavior;
        this.registries = registries;
        this.engine = engine;
    }

    /**
     * Whether any rule of any rule document or item answers {@code trigger} in the live shadow. One view lookup
     * and one set lookup; false before the first apply and for a shadow with no rule on the trigger.
     */
    public boolean subscribes(String trigger) {
        WorldRules world = registries.get().view(WorldRules.KEY);
        return world != null && world.subscribes(trigger);
    }

    /**
     * One event to dispatch. The suppliers are read only when a rule that matches the event needs them.
     *
     * @param trigger the trigger type ({@code entity.hit})
     * @param player the subject: the player the trigger names
     * @param where the event's location ({@code location.*}), read when a context is built
     * @param facts what the event says about itself to the rules' filters ({@code type}, {@code cause})
     * @param roots variables added to the tree, by root ({@code target}, {@code damage}); a root the base tree
     *        already has ({@code player}) is merged into it
     * @param cancellable the Bukkit event a rule may cancel, or null when the trigger cannot be cancelled
     * @param itemHosts the stacks whose item rules answer this event, in order; read only when an item has a
     *        rule on the trigger
     * @param withItem whether the tree carries {@code item.*}
     */
    public record Fire(
            String trigger,
            Player player,
            Supplier<Location> where,
            Map<String, String> facts,
            Supplier<Map<String, Map<String, Object>>> roots,
            Cancellable cancellable,
            Supplier<List<ItemStack>> itemHosts,
            boolean withItem) {

        public Fire {
            facts = facts == null ? Map.of() : facts;
            roots = roots == null ? Map::of : roots;
            itemHosts = itemHosts == null ? List::of : itemHosts;
        }
    }

    /** Dispatches one event: the item hosts' rules, then the rule documents'. */
    public void fire(Fire fire) {
        WorldRules world = registries.get().view(WorldRules.KEY);
        if (world == null || !world.subscribes(fire.trigger())) {
            return;
        }
        FilterFacts facts = FilterFacts.of(fire.facts());
        List<Rule> documents = matching(world.forTrigger(fire.trigger()), fire.trigger(), facts);
        if (world.itemsAnswer(fire.trigger())) {
            for (ItemStack host : fire.itemHosts().get()) {
                List<Rule> rules = matching(behavior.rulesFor(host), fire.trigger(), facts);
                if (!rules.isEmpty()) {
                    run(fire, rules, host);
                }
            }
        }
        if (!documents.isEmpty()) {
            run(fire, documents, fire.withItem() ? fire.player().getInventory().getItemInMainHand() : null);
        }
    }

    private void run(Fire fire, List<Rule> rules, ItemStack stack) {
        var root = new LinkedHashMap<>(behavior.baseRoot(fire.player(), fire.where().get()));
        fire.roots().get().forEach((key, values) -> merge(root, key, values));
        if (fire.withItem()) {
            root.put("item", BehaviorService.itemContext(stack));
        }
        var event = new BehaviorEvent(fire.trigger(), "", behavior.logicContext(root, fire.player()),
                fire.cancellable(), fire.player());
        event.withFilterFacts(FilterFacts.of(fire.facts()));
        if (fire.cancellable() != null) {
            event.withSynchronousPreEvent();
        }
        engine.fire(rules, event);
        if (event.isCancelRequested() && fire.cancellable() != null) {
            fire.cancellable().setCancelled(true);
        }
    }

    /** The rules that answer this trigger and whose filters hold for the event's facts; the engine asks again, cheaply. */
    private static List<Rule> matching(List<Rule> rules, String trigger, FilterFacts facts) {
        if (rules.isEmpty()) {
            return rules;
        }
        var out = new ArrayList<Rule>(rules.size());
        for (Rule rule : rules) {
            if (rule.on().matches(trigger, "") && rule.filters().matches(facts)) {
                out.add(rule);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void merge(Map<String, Object> root, String key, Map<String, Object> values) {
        if (root.get(key) instanceof Map<?, ?> existing) {
            ((Map<String, Object>) existing).putAll(values);
        } else {
            root.put(key, new LinkedHashMap<>(values));
        }
    }
}
