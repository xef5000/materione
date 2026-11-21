package dev.materione.plugin.commands;

import dev.materione.api.NamespacedId;
import dev.materione.behavior.logic.MapLogicContext;
import dev.materione.behavior.run.RunOwner;
import dev.materione.behavior.runtime.BehaviorEvent;
import dev.materione.behavior.runtime.RuleEngine;
import dev.materione.content.commands.CommandType;
import dev.materione.core.runtime.RuntimeRegistries;
import dev.materione.plugin.gui.open.ArgParser;
import dev.materione.plugin.gui.open.DocumentCommands;
import dev.materione.plugin.gui.open.OpenPlan;
import dev.materione.plugin.gui.open.OpenUsage;
import dev.materione.plugin.gui.open.PlayerNames;
import dev.materione.plugin.runtime.behavior.BehaviorService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Runs a command document when one of its names is typed (logic.md §40). The tree and the thread hop are the open
 * path's ({@code OpenCommandHandler}); this is everything after it, in the order §23 fixes for the gate of an open:
 *
 * <ol>
 *   <li><b>Permission.</b> {@code permission:} is tested on the sender; the console holds every node. A refusal says
 *       which node.
 *   <li><b>Console.</b> A sender with no player (the console, a command block, RCON) is refused unless the document
 *       says {@code console: true}.
 *   <li><b>Arguments.</b> The raw text is typed by the one {@link ArgParser} a GUI's {@code open:} uses; a refusal
 *       names the argument and prints the usage line generated from the declaration.
 *   <li><b>Requirement.</b> Evaluated against the sender and the typed arguments; a broken requirement fails closed.
 *       The bypass node {@code materione.command.bypass.<pack>.<id>} skips it and nothing else. A failed requirement
 *       runs the {@code deny:} steps as the sender's run; with none, the sender is told it is not met.
 *   <li><b>The flow.</b> One {@code command.run} event through the rule engine: {@code args.*} (typed),
 *       {@code command.name} and {@code command.label} in context, the sender as the run's subject.
 * </ol>
 *
 * <p>Called on the thread that owns the sender; every world access the flow makes goes through the actions and the
 * thread gateway as for any run (D8). Each outcome is logged as {@code MATERIONE-COMMAND alias=<a> command=<id>
 * outcome=ran|denied|usage|console|refused|gone}, which the harness and the QA suite read.
 */
public final class CommandRuns implements DocumentCommands {

    /**
     * What builds a run's context: the tree a player-subject event has (the player, the place, the world, the server,
     * {@code vars}) and the {@link MapLogicContext} that adds the player facet and the persistent variables. A seam, so
     * the gate is tested without a server; {@link #over} is the plugin's.
     */
    public interface Contexts {

        /** The variable tree of a player-subject run, read on the player's thread. */
        Map<String, Object> baseRoot(Player player);

        /** The run's context over {@code root}; {@code player} is null for a sender with none. */
        MapLogicContext context(Map<String, Object> root, Player player);
    }

    /** The plugin's contexts: the same tree and bindings every other player-subject run has. */
    public static Contexts over(BehaviorService behavior) {
        return new Contexts() {
            @Override
            public Map<String, Object> baseRoot(Player player) {
                return behavior.baseRoot(player, player.getLocation());
            }

            @Override
            public MapLogicContext context(Map<String, Object> root, Player player) {
                return behavior.logicContext(root, player);
            }
        };
    }

    private final Logger log;
    private final Supplier<RuntimeRegistries> registries;
    private final Contexts contexts;
    private final RuleEngine engine;
    private final PlayerNames players;

    public CommandRuns(
            Logger log, Supplier<RuntimeRegistries> registries, Contexts contexts, RuleEngine engine,
            PlayerNames players) {
        this.log = log;
        this.registries = registries;
        this.contexts = contexts;
        this.engine = engine;
        this.players = players;
    }

    private Optional<RuntimeCommands.Live> live(NamespacedId id) {
        RuntimeCommands view = registries.get() == null ? null : registries.get().view(RuntimeCommands.KEY);
        return view == null ? Optional.empty() : view.command(id);
    }

    @Override
    public Optional<OpenPlan> plan(NamespacedId command) {
        return live(command).map(RuntimeCommands.Live::plan);
    }

    @Override
    public void run(NamespacedId command, String alias, CommandSender sender, Map<String, String> raw) {
        Optional<RuntimeCommands.Live> found = live(command);
        if (found.isEmpty()) {
            mark(alias, command, "gone"); // a reload took the command between the tree and the run
            return;
        }
        RuntimeCommands.Live live = found.get();
        OpenPlan plan = live.plan();
        if (plan.permission() != null && !sender.hasPermission(plan.permission())) {
            sender.sendMessage(Component.text("no permission (" + plan.permission() + ")", NamedTextColor.RED));
            mark(alias, command, "denied");
            return;
        }
        Player player = sender instanceof Player p ? p : null;
        if (player == null && !live.console()) {
            sender.sendMessage(Component.text("only a player can run /" + alias + " (it has no console form)",
                    NamedTextColor.RED));
            mark(alias, command, "console");
            return;
        }
        ArgParser.Result parsed = ArgParser.parse(plan, raw, players);
        if (!parsed.ok()) {
            sender.sendMessage(Component.text(OpenUsage.refusal("/" + alias, plan, parsed.error()),
                    NamedTextColor.YELLOW));
            mark(alias, command, "usage");
            return;
        }
        MapLogicContext context = context(live, alias, sender, player, ArgParser.typed(plan, parsed.values()));
        Object anchor = player;
        if (plan.hasRequirement() && !sender.hasPermission(OpenPlan.commandBypassNode(command))
                && !plan.requirementMet(context)) {
            if (plan.deny() != null) {
                engine.interpreter().start(plan.deny(), context, RunOwner.of(playerKey(player), anchor));
            } else {
                sender.sendMessage(Component.text("the requirement for /" + alias + " is not met", NamedTextColor.RED));
            }
            mark(alias, command, "refused");
            return;
        }
        var event = new BehaviorEvent(CommandType.TRIGGER, "", context, null, anchor);
        engine.fire(List.of(live.flow()), event);
        mark(alias, command, "ran");
    }

    /**
     * The run's context: the sender's tree as any player run has it (the player, the place, the world, the server,
     * {@code vars}), the typed {@code args} and {@code command.*}. The console has a name and the server, and nothing
     * a player has.
     */
    private MapLogicContext context(
            RuntimeCommands.Live live, String alias, CommandSender sender, Player player, Map<String, Object> args) {
        Map<String, Object> root;
        if (player != null) {
            root = new LinkedHashMap<>(contexts.baseRoot(player));
        } else {
            root = new LinkedHashMap<>();
            var senderVars = new LinkedHashMap<String, Object>();
            senderVars.put("name", sender.getName());
            root.put("player", senderVars);
            var server = new LinkedHashMap<String, Object>();
            try {
                server.put("online", Bukkit.getOnlinePlayers().size());
                server.put("max", Bukkit.getMaxPlayers());
            } catch (RuntimeException noServer) {
                // a server-less test: the variables read as absent
            }
            root.put("server", server);
            root.put("vars", new LinkedHashMap<String, Object>());
        }
        root.put("args", args);
        var info = new LinkedHashMap<String, Object>();
        info.put("name", live.plan().commands().get(0));
        info.put("label", alias);
        root.put("command", info);
        return contexts.context(root, player);
    }

    private static Object playerKey(Player player) {
        return player == null ? null : player.getUniqueId().toString();
    }

    /** The stable marker the harness and the QA runs read: what a command did, per alias. */
    private void mark(String alias, NamespacedId command, String outcome) {
        log.info("MATERIONE-COMMAND alias=" + alias + " command=" + command + " outcome=" + outcome);
    }
}
