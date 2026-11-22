package dev.materione.plugin.commands;

import dev.materione.api.NamespacedId;
import dev.materione.behavior.rule.Rule;
import dev.materione.core.runtime.RuntimeRegistries;
import dev.materione.plugin.commands.OpenDeclaration;
import dev.materione.plugin.commands.OpenPlan;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The command documents of the applied shadow (logic.md §40), compiled and ready to run. A
 * {@link dev.materione.core.runtime.RuntimeRegistries} view, so it swaps atomically with everything else: a command
 * runs against one consistent snapshot, and a reload needs no listener churn (the names reach the server's command
 * tree through the open-command registrar, which reads {@link #declarations}).
 *
 * <p>Only a command that loaded is here: one whose {@code do:} did not compile, or that names nothing, is quarantined
 * at build time (its diagnostics are already published) and claims no name.
 */
public final class RuntimeCommands {

    /** The registry-view key. */
    public static final Class<RuntimeCommands> KEY = RuntimeCommands.class;

    private static final RuntimeCommands EMPTY = new RuntimeCommands(List.of());

    /**
     * One live command.
     *
     * @param id the document's resource id
     * @param description the author's note, or null
     * @param plan the names, typed arguments, permission and gate, compiled
     * @param console whether a sender with no player may run it
     * @param flow the compiled {@code do:}, one rule of the {@code command.run} trigger
     */
    public record Live(NamespacedId id, String description, OpenPlan plan, boolean console, Rule flow) {}

    private final Map<NamespacedId, Live> commands;
    private final List<Live> order;

    public RuntimeCommands(Collection<Live> commands) {
        var byId = new LinkedHashMap<NamespacedId, Live>();
        for (Live live : commands) {
            byId.put(live.id(), live);
        }
        this.commands = Map.copyOf(byId);
        this.order = List.copyOf(byId.values());
    }

    public static RuntimeCommands empty() {
        return EMPTY;
    }

    /** Every live command, in the order the pipeline met the documents. */
    public List<Live> all() {
        return order;
    }

    /** One command, or empty when it is unknown or was quarantined. */
    public Optional<Live> command(NamespacedId id) {
        return Optional.ofNullable(commands.get(id));
    }

    public int size() {
        return commands.size();
    }

    /** What the open-command registrar claims names from: each command's plan, as a declaration. */
    public List<OpenDeclaration> declarations() {
        var out = new ArrayList<OpenDeclaration>(order.size());
        for (Live live : order) {
            out.add(new OpenDeclaration(OpenDeclaration.Kind.COMMAND, live.id(), live.plan()));
        }
        return out;
    }

    /** The declarations of a registries snapshot, none before there is one or when it has no command view. */
    public static List<OpenDeclaration> declarations(RuntimeRegistries registries) {
        RuntimeCommands view = registries == null ? null : registries.view(KEY);
        return view == null ? List.of() : view.declarations();
    }
}
