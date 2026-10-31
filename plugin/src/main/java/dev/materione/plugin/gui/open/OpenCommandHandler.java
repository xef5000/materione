package dev.materione.plugin.gui.open;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.materione.api.NamespacedId;
import dev.materione.api.scheduling.SchedulingGateway;
import dev.materione.plugin.gui.RuntimeGuis;
import dev.materione.plugin.gui.Viewer;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * What an open command does when it runs (logic.md §6.1): it reads the arguments the client sent, types them
 * with the GUI's live block, and opens the GUI through the one open path ({@link GuiOpener} via
 * {@link OpenRequest}). A usage problem answers the sender with the usage line of the alias they typed, and
 * a console sender, which has no window, is told it must be a player.
 *
 * <p>It also owns which aliases are declared ({@link #rebind}), so the Brigadier {@code requires} predicate and
 * the executor agree. The registrar changes the bindings; nothing else does.
 */
public final class OpenCommandHandler {

    private final Plugin plugin;
    private final SchedulingGateway scheduling;
    private final Supplier<RuntimeGuis> live;
    private final GuiOpener opener;
    private final PlayerNames players;
    private volatile Map<String, NamespacedId> bindings = Map.of();

    public OpenCommandHandler(
            Plugin plugin, SchedulingGateway scheduling, Supplier<RuntimeGuis> live, GuiOpener opener,
            PlayerNames players) {
        this.plugin = plugin;
        this.scheduling = scheduling;
        this.live = live;
        this.opener = opener;
        this.players = players;
    }

    /** Replaces the declared aliases: each names the GUI it opens. Read by every command and suggestion. */
    public void rebind(Map<String, NamespacedId> declared) {
        bindings = Map.copyOf(declared);
    }

    /** The declared aliases and the GUI each opens. */
    public Map<String, NamespacedId> bindings() {
        return bindings;
    }

    /** Whether {@code alias} is declared right now. */
    public boolean declared(String alias) {
        return bindings.containsKey(alias);
    }

    /** The GUI an alias opens, if it is declared. */
    public Optional<NamespacedId> gui(String alias) {
        return Optional.ofNullable(bindings.get(alias));
    }

    /** Runs an open command: the Brigadier executor for every prefix of its arguments. */
    public int run(String alias, CommandContext<CommandSourceStack> context) {
        Optional<RuntimeGuis.Live> gui = gui(alias).flatMap(id -> live.get().gui(id));
        if (gui.isEmpty() || gui.get().open() == null) {
            return 0;
        }
        OpenPlan plan = gui.get().open();
        Map<String, String> raw = rawArguments(context, plan);
        CommandSender sender = context.getSource().getSender();
        ArgParser.Result parsed = ArgParser.parse(plan, raw, players);
        if (!parsed.ok()) {
            sender.sendMessage(Component.text(OpenUsage.refusal("/" + alias, plan, parsed.error()),
                    NamedTextColor.YELLOW));
            mark(alias, "usage");
            return 0;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("only a player can open " + gui.get().id() + " (no window for a console)",
                    NamedTextColor.RED));
            mark(alias, "console");
            return 1;
        }
        NamespacedId id = gui.get().id();
        scheduling.executeEntity(plugin, player, () -> {
            OpenResult result = opener.open(
                    OpenRequest.of(Viewer.of(player), id, raw, OpenRequest.Cause.API));
            if (result.ok()) {
                mark(alias, "opened");
            } else {
                player.sendMessage(Component.text(result.reason(), NamedTextColor.RED));
                mark(alias, "refused");
            }
        });
        return 1;
    }

    /** Completions for one argument of an alias: the choices, the suggest list, the players, or true and false. */
    public SuggestionProvider<CommandSourceStack> suggestions(String alias, String argument) {
        return (CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) -> {
            String prefix = builder.getRemainingLowerCase();
            Optional<OpenArgSpec> arg = gui(alias).flatMap(id -> live.get().gui(id))
                    .map(RuntimeGuis.Live::open)
                    .flatMap(plan -> plan == null ? Optional.empty() : plan.arg(argument));
            if (arg.isPresent()) {
                for (String candidate : candidates(arg.get())) {
                    if (candidate.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                        builder.suggest(candidate);
                    }
                }
            }
            return builder.buildFuture();
        };
    }

    private List<String> candidates(OpenArgSpec arg) {
        return switch (arg.type()) {
            case PLAYER -> List.copyOf(players.names());
            case BOOLEAN -> List.of("true", "false");
            default -> arg.suggest();
        };
    }

    /** The arguments the client sent, by the live block's names. An argument the tree does not carry is absent. */
    private static Map<String, String> rawArguments(CommandContext<CommandSourceStack> context, OpenPlan plan) {
        var raw = new LinkedHashMap<String, String>();
        for (OpenArgSpec arg : plan.args()) {
            boolean sent = context.getNodes().stream().anyMatch(parsed ->
                    parsed.getNode() instanceof ArgumentCommandNode<?, ?> node && node.getName().equals(arg.name()));
            if (sent) {
                raw.put(arg.name(), String.valueOf(context.getArgument(arg.name(), Object.class)));
            }
        }
        return raw;
    }

    /** The stable marker the harness and the QA runs read: what an open command did, per alias. */
    private void mark(String alias, String outcome) {
        plugin.getLogger().info("MATERIONE-OPEN-COMMAND alias=" + alias + " outcome=" + outcome);
    }
}
