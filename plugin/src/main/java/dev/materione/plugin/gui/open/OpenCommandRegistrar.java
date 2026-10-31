package dev.materione.plugin.gui.open;

import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.Severity;
import dev.materione.plugin.gui.RuntimeGuis;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Puts the open commands of the live GUIs into the server's command tree (logic.md §6.1, §23). The tree can only
 * be changed while the server's command lifecycle is running, so there are two moments:
 *
 * <ul>
 *   <li><b>The COMMANDS lifecycle event</b> ({@link #onCommands}): Paper calls it at start and on a server reload,
 *       and only there may commands be registered. Every declared alias is registered from the live view.
 *   <li><b>A content swap</b> ({@link #resync}): the declared set is updated at once, which hides a removed alias
 *       (Brigadier cannot remove a child; the node's {@code requires} checks the declared set). An alias the tree
 *       does not have yet asks Paper for a reload of its data, which fires the COMMANDS event again with the live
 *       view; if that does not put the alias in the tree, it is reported as needing a restart.
 * </ul>
 *
 * <p>A name that is already a command (vanilla, another plugin, or another GUI's open command) is skipped with
 * {@link DiagnosticCodes#OPEN_COMMAND_CONFLICT}. Paper would otherwise let a main label override it, so the check is
 * made before anything is registered. An argument shape that changed for an alias already in the tree cannot change
 * in place, and is reported as {@link DiagnosticCodes#OPEN_TREE_RESYNC}. Nothing here reads the command registrar
 * outside {@link #onCommands}: Paper refuses that, and a swap must never fail because of it.
 */
public final class OpenCommandRegistrar {

    /** An alias this registrar has put in the tree, with the argument shape it was built with. */
    private record Owned(List<String> shape) {}

    /** The GUI an alias opens and the arguments its command takes. */
    private record Claim(NamespacedId gui, List<OpenArgSpec> args) {}

    private final Plugin plugin;
    private final OpenCommandHandler handler;
    private final Supplier<RuntimeGuis> live;
    private final Consumer<Diagnostic> diagnostics;
    private final Map<String, Owned> owned = new HashMap<>();
    /** Aliases the last COMMANDS event refused: a swap does not ask for another reload for them until the next event. */
    private Set<String> refused = Set.of();
    private volatile boolean treeOpen;

    public OpenCommandRegistrar(
            Plugin plugin, OpenCommandHandler handler, Supplier<RuntimeGuis> live, Consumer<Diagnostic> diagnostics) {
        this.plugin = plugin;
        this.handler = handler;
        this.live = live;
        this.diagnostics = diagnostics;
    }

    /**
     * Registers every declared alias. Called from the server's COMMANDS lifecycle event, at start and on a server
     * reload, while the registrar is valid.
     */
    public synchronized void onCommands(Commands registrar) {
        treeOpen = true;
        Set<String> previous = Set.copyOf(owned.keySet());
        owned.clear();
        Map<String, Claim> claims = claims(live.get(), registrar, previous, true);
        var bound = new LinkedHashMap<String, NamespacedId>();
        for (Map.Entry<String, Claim> entry : claims.entrySet()) {
            String alias = entry.getKey();
            Claim claim = entry.getValue();
            try {
                Set<String> labels = registrar.register(node(alias, claim.args()), "Opens " + claim.gui(), List.of());
                if (labels.contains(alias)) {
                    owned.put(alias, new Owned(OpenCommandTree.shape(claim.args())));
                    bound.put(alias, claim.gui());
                } else {
                    conflict(claim.gui(), alias, "could not be registered");
                }
            } catch (RuntimeException e) {
                conflict(claim.gui(), alias, "could not be registered: " + e.getMessage());
            }
        }
        // every declared alias this event did not bind is refused until the next event: a swap must not ask again
        var rejected = new java.util.HashSet<String>();
        for (RuntimeGuis.Live gui : live.get().all()) {
            if (gui.open() != null) {
                rejected.addAll(gui.open().commands());
            }
        }
        rejected.removeAll(bound.keySet());
        refused = Set.copyOf(rejected);
        handler.rebind(bound);
        plugin.getLogger().info("MATERIONE-OPEN-TREE declared=" + claims.size() + " registered=" + owned.size());
    }

    /**
     * Brings the declared set in line with the live GUIs after a content swap (logic.md §6.1, §23). It never throws:
     * a swap is not allowed to fail on the command tree. An alias the tree does not have yet asks for a reload of
     * the server's data, which fires the COMMANDS event again. Before that event has run there is no tree, so the
     * declared set alone is updated, and the event registers it.
     */
    public synchronized void resync(RuntimeGuis view) {
        Map<String, Claim> claims = claims(view, null, owned.keySet(), false);
        claims.keySet().removeAll(refused);
        boolean missing = false;
        var next = new LinkedHashMap<String, NamespacedId>();
        for (Map.Entry<String, Claim> entry : claims.entrySet()) {
            String alias = entry.getKey();
            Claim claim = entry.getValue();
            Owned existing = owned.get(alias);
            if (existing == null) {
                missing |= treeOpen;
            } else if (!existing.shape().equals(OpenCommandTree.shape(claim.args()))) {
                diagnostics.accept(new Diagnostic(DiagnosticCodes.OPEN_TREE_RESYNC, Severity.WARN,
                        "open command '" + alias + "' of " + claim.gui() + " changed its argument names or types; "
                                + "the tree keeps the old shape until the next restart",
                        claim.gui(), null, "restart the server to apply the new argument shape"));
            }
            next.put(alias, claim.gui());
        }
        Map<String, NamespacedId> before = handler.bindings();
        handler.rebind(next);
        if (missing) {
            reloadTree(claims);
        }
        if (treeOpen && !before.equals(next)) {
            sendTree();
        }
    }

    /**
     * Asks Paper to reload the server's data, which fires the COMMANDS event with the live view (the reload cause).
     * An alias still missing afterwards is reported, and applies at the next restart.
     */
    private void reloadTree(Map<String, Claim> claims) {
        try {
            plugin.getServer().reloadData();
        } catch (RuntimeException e) {
            plugin.getLogger().warning("MATERIONE-OPEN-TREE reload failed: " + e);
        }
        for (Map.Entry<String, Claim> entry : claims.entrySet()) {
            if (!owned.containsKey(entry.getKey())) {
                diagnostics.accept(new Diagnostic(DiagnosticCodes.OPEN_TREE_RESYNC, Severity.WARN,
                        "open command '" + entry.getKey() + "' of " + entry.getValue().gui()
                                + " could not be added to the live tree; it applies at the next restart",
                        entry.getValue().gui(), null, "restart the server to register it"));
            }
        }
    }

    /** Sends each online player the command tree again, so their suggestions follow the change. */
    private void sendTree() {
        try {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                player.updateCommands();
            }
        } catch (RuntimeException e) {
            plugin.getLogger().warning("MATERIONE-OPEN-TREE could not re-send the tree: " + e);
        }
    }

    /**
     * The aliases the live GUIs claim, in order, with the conflicts left out. An alias one GUI claims twice, or that
     * is already a command, is skipped and reported (when {@code report} is set).
     *
     * @param registrar the registrar of the COMMANDS event, or null outside it (then only the command map is checked)
     * @param ours the aliases this registrar put in the tree, which are not conflicts
     */
    private Map<String, Claim> claims(RuntimeGuis view, Commands registrar, Set<String> ours, boolean report) {
        var claims = new LinkedHashMap<String, Claim>();
        for (RuntimeGuis.Live gui : view.all()) {
            OpenPlan plan = gui.open();
            if (plan == null) {
                continue;
            }
            for (String alias : plan.commands()) {
                if (claims.containsKey(alias)) {
                    if (report) {
                        conflict(gui.id(), alias, "is already the open command of " + claims.get(alias).gui());
                    }
                } else if (taken(alias, registrar, ours)) {
                    if (report) {
                        conflict(gui.id(), alias, "is already a command");
                    }
                } else {
                    claims.put(alias, new Claim(gui.id(), plan.args()));
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
                source -> handler.declared(alias),
                context -> handler.run(alias, context),
                argument -> handler.suggestions(alias, argument));
    }

    private void conflict(NamespacedId gui, String alias, String reason) {
        diagnostics.accept(new Diagnostic(DiagnosticCodes.OPEN_COMMAND_CONFLICT, Severity.WARN,
                "open command '" + alias + "' of " + gui + " " + reason + "; the alias is skipped",
                gui, null, "rename the command or the other one; /materione gui open still opens the GUI"));
    }
}
