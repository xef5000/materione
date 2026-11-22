package dev.materione.plugin.commands;

import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.scheduling.ScheduledHandle;
import dev.materione.api.scheduling.SchedulingGateway;
import dev.materione.plugin.gui.RuntimeGuis;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Puts the open commands of the live GUIs into the server's command tree (logic.md §6.1, §23). The tree can only be
 * changed while the server's command lifecycle is running, so there are two moments:
 *
 * <ul>
 *   <li><b>The COMMANDS lifecycle event</b> ({@link #onCommands}): Paper calls it at start and on a server reload, and
 *       only there may commands be registered. It registers from the <em>built</em> registries (the latest reload's,
 *       applied or not), because the first apply runs after the event. So at boot the tree already holds what the
 *       first swap publishes, and nothing needs a reload.
 *   <li><b>A content swap</b> ({@link #resync}): the declared set is updated at once, which hides a removed alias
 *       (Brigadier cannot remove a child; the node's {@code requires} checks the declared set). Only an alias the tree
 *       lacks, or built with another argument shape, asks for a reload: {@code Server#reloadData}, which fires the
 *       COMMANDS event again. Reloads are rate-limited, so several swaps in a short window are coalesced into one.
 * </ul>
 *
 * <p>A name that is already a command (vanilla, another plugin, or another GUI's open command) is skipped with
 * {@link DiagnosticCodes#OPEN_COMMAND_CONFLICT}, because Paper would otherwise let a main label override it. Nothing
 * here reads the command registrar outside {@link #onCommands}: Paper refuses that, and a swap must never fail on it.
 *
 * <p>Log markers, read by the harness: {@code MATERIONE-OPEN-TREE declared=<n> registered=<n>} at each COMMANDS event;
 * {@code MATERIONE-OPEN-TREE reload aliases=[...]} for a reload this registrar asked for; and
 * {@code MATERIONE-OPEN-TREE reload-deferred aliases=[...]} for one the rate limit held back.
 */
public final class OpenCommandRegistrar {

    /** A reload is asked for at most this often; a swap inside the window is coalesced into the next allowed one. */
    static final long MIN_RELOAD_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final long TICK_NANOS = TimeUnit.MILLISECONDS.toNanos(50);

    /** An alias this registrar has put in the tree, with the argument shape it was built with. */
    private record Owned(List<String> shape) {}

    /**
     * What an alias runs (a GUI it opens, or a command document's flow), the arguments its command takes, and who may see
     * it ({@code gate}: its permission and whether it is always visible), which a player's synced tree depends on.
     */
    private record Claim(OpenDeclaration.Kind kind, NamespacedId owner, List<OpenArgSpec> args, String gate) {}

    private final Plugin plugin;
    private final OpenCommandHandler handler;
    private final Supplier<RuntimeGuis> live;
    private final Supplier<RuntimeGuis> built;
    private final Consumer<Diagnostic> diagnostics;
    private final LongSupplier clock;
    private final SchedulingGateway scheduling;
    private final Map<String, Owned> owned = new HashMap<>();
    /** Who may see each declared alias, as of the last bind: a change re-sends the players their tree (logic.md section 40). */
    private Map<String, String> gates = Map.of();
    /** Declared aliases the last COMMANDS event refused: no reload is asked for them until the next event. */
    private Set<String> refused = Set.of();
    /** Aliases a reload did not put in the tree, and which must not ask for another until the next event. */
    private Set<String> unreloadable = Set.of();
    /** The command documents of the applied and of the built registries (logic.md §40); none until installed. */
    private volatile Supplier<List<OpenDeclaration>> liveDocuments = List::of;
    private volatile Supplier<List<OpenDeclaration>> builtDocuments = List::of;
    private long lastReload = Long.MIN_VALUE;
    private volatile boolean treeOpen;
    private ScheduledHandle pendingReload;
    private boolean closed;

    /**
     * @param live the applied registries' GUIs, which the executor and the suggestions read
     * @param built the registries of the latest reload, which the COMMANDS event registers from
     */
    public OpenCommandRegistrar(
            Plugin plugin, OpenCommandHandler handler, Supplier<RuntimeGuis> live, Supplier<RuntimeGuis> built,
            Consumer<Diagnostic> diagnostics, SchedulingGateway scheduling) {
        this(plugin, handler, live, built, diagnostics, System::nanoTime, scheduling);
    }

    /** For tests: the clock the reload rate limit reads. */
    OpenCommandRegistrar(
            Plugin plugin, OpenCommandHandler handler, Supplier<RuntimeGuis> live, Supplier<RuntimeGuis> built,
            Consumer<Diagnostic> diagnostics, LongSupplier clock, SchedulingGateway scheduling) {
        this.plugin = plugin;
        this.handler = handler;
        this.live = live;
        this.built = built;
        this.diagnostics = diagnostics;
        this.clock = clock;
        this.scheduling = scheduling;
    }

    /**
     * Adds the command documents to what claims names (logic.md §40): they claim after every GUI, so a GUI's {@code open:}
     * alias keeps its name, as it always did, and a document that wants it is the one refused.
     *
     * @param live the command documents of the applied registries
     * @param built the command documents of the latest reload's registries, which the COMMANDS event registers from
     */
    public void commandDocuments(Supplier<List<OpenDeclaration>> live, Supplier<List<OpenDeclaration>> built) {
        this.liveDocuments = live == null ? List::of : live;
        this.builtDocuments = built == null ? List::of : built;
    }

    /**
     * Registers every declared alias. Called from the server's COMMANDS lifecycle event, at start and on a server
     * reload, while the registrar is valid. Registers from the built registries, so it is correct before the first
     * apply has run.
     */
    public synchronized void onCommands(Commands registrar) {
        if (closed) {
            return;
        }
        cancelReload();
        treeOpen = true;
        unreloadable = Set.of();
        List<OpenDeclaration> declarations = declarations(built.get(), builtDocuments);
        Set<String> previous = Set.copyOf(owned.keySet());
        owned.clear();
        Map<String, Claim> claims = claims(declarations, registrar, previous, true);
        var bound = new LinkedHashMap<String, OpenCommandHandler.Binding>();
        for (Map.Entry<String, Claim> entry : claims.entrySet()) {
            String alias = entry.getKey();
            Claim claim = entry.getValue();
            try {
                String description = (claim.kind() == OpenDeclaration.Kind.GUI ? "Opens " : "Runs ") + claim.owner();
                Set<String> labels = registrar.register(node(alias, claim.args()), description, List.of());
                if (labels.contains(alias)) {
                    owned.put(alias, new Owned(OpenCommandTree.shape(claim.args())));
                    bound.put(alias, new OpenCommandHandler.Binding(claim.kind(), claim.owner()));
                } else {
                    conflict(claim, alias, "could not be registered");
                }
            } catch (RuntimeException e) {
                conflict(claim, alias, "could not be registered: " + e.getMessage());
            }
        }
        // every declared alias this event did not bind is refused until the next event: a swap must not ask again
        var rejected = new HashSet<String>();
        for (OpenDeclaration declaration : declarations) {
            rejected.addAll(declaration.plan().commands());
        }
        rejected.removeAll(bound.keySet());
        refused = Set.copyOf(rejected);
        handler.rebind(bound);
        gates = gatesOf(claims, bound.keySet());
        plugin.getLogger().info("MATERIONE-OPEN-TREE declared=" + claims.size() + " registered=" + owned.size());
    }

    /**
     * Brings the declared set in line with the live GUIs after a content swap (logic.md §23, decision 15). It never
     * throws. It asks for a reload only when a declared alias is missing from the tree or was built with another
     * argument shape; a removed alias needs none, because its {@code requires} now fails. Before the COMMANDS event
     * there is no tree, so only the declared set changes, and that event registers it.
     */
    public synchronized void resync(RuntimeGuis view) {
        if (closed) {
            return;
        }
        Map<String, Claim> claims = claims(declarations(view, liveDocuments), null, owned.keySet(), false);
        claims.keySet().removeAll(refused);
        var next = new LinkedHashMap<String, OpenCommandHandler.Binding>();
        var stale = new LinkedHashMap<String, Claim>();
        for (Map.Entry<String, Claim> entry : claims.entrySet()) {
            String alias = entry.getKey();
            Claim claim = entry.getValue();
            Owned existing = owned.get(alias);
            boolean missing = existing == null;
            boolean reshaped = !missing && !existing.shape().equals(OpenCommandTree.shape(claim.args()));
            if (treeOpen && (missing || reshaped) && !unreloadable.contains(alias)) {
                stale.put(alias, claim);
            }
            next.put(alias, new OpenCommandHandler.Binding(claim.kind(), claim.owner()));
        }
        Map<String, OpenCommandHandler.Binding> before = handler.bindings();
        Map<String, String> nextGates = gatesOf(claims, next.keySet());
        boolean regated = !nextGates.equals(gates);
        gates = nextGates;
        handler.rebind(next);
        if (!stale.isEmpty()) {
            reloadTree(stale);
        } else {
            cancelReload();
        }
        if (treeOpen && (!before.equals(next) || regated)) {
            sendTree(); // a permission or a visible: that changed shows or hides the command in a synced tree only now
        }
    }

    /**
     * Asks Paper to reload the server's data, which fires the COMMANDS event with the live view. Within the rate
     * limit one scheduled retry asks again for everything still stale after the window.
     * An alias the reload did not put in the tree is reported and not asked for again until the next event.
     */
    private void reloadTree(Map<String, Claim> stale) {
        long now = clock.getAsLong();
        if (lastReload != Long.MIN_VALUE && now - lastReload < MIN_RELOAD_NANOS) {
            plugin.getLogger().info("MATERIONE-OPEN-TREE reload-deferred aliases=" + stale.keySet()
                    + " reason=rate-limit");
            if (pendingReload == null) {
                long remaining = MIN_RELOAD_NANOS - (now - lastReload);
                long delayTicks = Math.max(1, (remaining + TICK_NANOS - 1) / TICK_NANOS);
                try {
                    pendingReload = scheduling.executeGlobalLater(plugin, delayTicks, this::retryReload);
                } catch (RuntimeException e) {
                    plugin.getLogger().warning("MATERIONE-OPEN-TREE could not schedule reload: " + e);
                    for (Map.Entry<String, Claim> entry : stale.entrySet()) {
                        diagnostics.accept(new Diagnostic(DiagnosticCodes.OPEN_TREE_RESYNC, Severity.WARN,
                                noun(entry.getValue()) + " '" + entry.getKey() + "' of " + entry.getValue().owner()
                                        + " could not schedule its command-tree reload",
                                entry.getValue().owner(), null, "reload content after the rate-limit window or restart"));
                    }
                }
            }
            return;
        }
        cancelReload();
        lastReload = now;
        plugin.getLogger().info("MATERIONE-OPEN-TREE reload aliases=" + stale.keySet());
        try {
            plugin.getServer().reloadData();
        } catch (RuntimeException e) {
            plugin.getLogger().warning("MATERIONE-OPEN-TREE reload failed: " + e);
        }
        var failed = new LinkedHashSet<String>();
        for (Map.Entry<String, Claim> entry : stale.entrySet()) {
            Owned after = owned.get(entry.getKey());
            if (after == null || !after.shape().equals(OpenCommandTree.shape(entry.getValue().args()))) {
                failed.add(entry.getKey());
                Claim claim = entry.getValue();
                diagnostics.accept(new Diagnostic(DiagnosticCodes.OPEN_TREE_RESYNC, Severity.WARN,
                        noun(claim) + " '" + entry.getKey() + "' of " + claim.owner() + " is not in the live tree after a "
                                + "reload; it applies at the next restart",
                        claim.owner(), null, "restart the server to register it"));
            }
        }
        if (!failed.isEmpty()) {
            var remembered = new HashSet<>(unreloadable);
            remembered.addAll(failed);
            unreloadable = Set.copyOf(remembered);
        }
    }

    private synchronized void retryReload() {
        pendingReload = null;
        if (!closed) {
            resync(live.get());
        }
    }

    private void cancelReload() {
        if (pendingReload != null) {
            pendingReload.cancel();
            pendingReload = null;
        }
    }

    /** Cancels a deferred reload and ignores further lifecycle/swap callbacks during shutdown. */
    public synchronized void close() {
        closed = true;
        cancelReload();
    }

    private static Map<String, String> gatesOf(Map<String, Claim> claims, Set<String> aliases) {
        var out = new HashMap<String, String>();
        for (String alias : aliases) {
            Claim claim = claims.get(alias);
            if (claim != null) {
                out.put(alias, claim.gate());
            }
        }
        return out;
    }

    /** Sends each online player the command tree again, so their suggestions follow the change. */
    private void sendTree() {
        try {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                scheduling.executeEntity(plugin, player, player::updateCommands);
            }
        } catch (RuntimeException e) {
            plugin.getLogger().warning("MATERIONE-OPEN-TREE could not re-send the tree: " + e);
        }
    }

    /**
     * Everything that claims names: the GUIs' {@code open:} blocks first, in the view's order, then the command
     * documents (logic.md §40). The order is the rule for a collision: the earlier claim keeps the name.
     */
    private static List<OpenDeclaration> declarations(RuntimeGuis guis, Supplier<List<OpenDeclaration>> documents) {
        var all = new java.util.ArrayList<>(OpenDeclaration.of(guis));
        all.addAll(documents.get());
        return all;
    }

    /**
     * The aliases the declarations claim, in order, with the conflicts left out. An alias one declaration claims twice,
     * that another already claimed, or that is already a command, is skipped and reported (when {@code report} is set).
     *
     * @param registrar the registrar of the COMMANDS event, or null outside it (then only the command map is checked)
     * @param ours the aliases this registrar put in the tree, which are not conflicts
     */
    private Map<String, Claim> claims(
            List<OpenDeclaration> declarations, Commands registrar, Set<String> ours, boolean report) {
        var claims = new LinkedHashMap<String, Claim>();
        for (OpenDeclaration declaration : declarations) {
            OpenPlan plan = declaration.plan();
            for (String alias : plan.commands()) {
                var claim = new Claim(declaration.kind(), declaration.id(), plan.args(),
                        plan.visibleAlways() ? "always" : String.valueOf(plan.permission()));
                if (claims.containsKey(alias)) {
                    if (report) {
                        conflict(claim, alias, "is already the " + (claims.get(alias).kind() == OpenDeclaration.Kind.GUI
                                ? "open command" : "command") + " of " + claims.get(alias).owner());
                    }
                } else if (taken(alias, registrar, ours)) {
                    if (report) {
                        conflict(claim, alias, "is already a command");
                    }
                } else {
                    claims.put(alias, claim);
                }
            }
        }
        return claims;
    }

    /** Whether the name is a command already: in the server's tree or its command map, and not ours. */
    private boolean taken(String alias, Commands registrar, Set<String> ours) {
        if (ours.contains(alias)) {
            return false;
        }
        if (registrar != null && registrar.getDispatcher().getRoot().getChild(alias) != null) {
            return true;
        }
        return plugin.getServer().getCommandMap().getCommand(alias) != null;
    }

    /** The alias's literal, built with the arguments its GUI declares when the node is made. */
    private LiteralCommandNode<CommandSourceStack> node(String alias, List<OpenArgSpec> args) {
        return OpenCommandTree.build(
                alias,
                args,
                source -> handler.visibleTo(alias, source.getSender()),
                context -> handler.run(alias, context),
                argument -> handler.suggestions(alias, argument));
    }

    /** What a message calls the name: an open command (a GUI's) or a command (a document's). */
    private static String noun(Claim claim) {
        return claim.kind() == OpenDeclaration.Kind.GUI ? "open command" : "command";
    }

    private void conflict(Claim claim, String alias, String reason) {
        String hint = claim.kind() == OpenDeclaration.Kind.GUI
                ? "rename the command or the other one; /materione gui open still opens the GUI"
                : "rename the command or the other one; the document's other names still run it";
        diagnostics.accept(new Diagnostic(DiagnosticCodes.OPEN_COMMAND_CONFLICT, Severity.WARN,
                noun(claim) + " '" + alias + "' of " + claim.owner() + " " + reason + "; the alias is skipped",
                claim.owner(), null, hint));
    }
}
