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
 * What a declared command name does when it runs (logic.md §6.1, §40). A name belongs to a GUI's {@code open:} block
 * or to a {@code command} document, and the first is defined as the sugar of the second: both are names with typed
 * arguments and a gate whose flow is "open this GUI" or "run these steps". For a GUI it reads the arguments the client
 * sent, types them with the GUI's live block, and opens the GUI through the one open path ({@link GuiOpener} via
 * {@link OpenRequest}); a usage problem answers the sender with the usage line of the alias they typed, and a console
 * sender, which has no window, is told it must be a player. For a command document it hands the sender and the raw
 * arguments to {@link DocumentCommands}, which gates and runs the flow.
 *
 * <p>It also owns which aliases are declared ({@link #rebind}), so the Brigadier {@code requires} predicate and
 * the executor agree. The registrar changes the bindings; nothing else does.
 */
public final class OpenCommandHandler {

    /** What a declared alias runs. */
    public record Binding(OpenDeclaration.Kind kind, NamespacedId id) {}

    private final Plugin plugin;
    private final SchedulingGateway scheduling;
    private final Supplier<RuntimeGuis> live;
    private final GuiOpener opener;
    private final PlayerNames players;
    private volatile Map<String, Binding> bindings = Map.of();
    private volatile DocumentCommands documents = DocumentCommands.NONE;

    public OpenCommandHandler(
            Plugin plugin, SchedulingGateway scheduling, Supplier<RuntimeGuis> live, GuiOpener opener,
            PlayerNames players) {
        this.plugin = plugin;
        this.scheduling = scheduling;
        this.live = live;
        this.opener = opener;
        this.players = players;
    }

    /** Installs what runs command documents; until it is, a name that belongs to one runs nothing. */
    public void documents(DocumentCommands documents) {
        this.documents = documents == null ? DocumentCommands.NONE : documents;
    }

    /** Replaces the declared aliases: each names what it runs. Read by every command and suggestion. */
    public void rebind(Map<String, Binding> declared) {
        bindings = Map.copyOf(declared);
    }

    /** The declared aliases and what each runs. */
    public Map<String, Binding> bindings() {
        return bindings;
    }

    /** Whether {@code alias} is declared right now. */
    public boolean declared(String alias) {
        return bindings.containsKey(alias);
    }

    /** The GUI an alias opens, if it is declared and belongs to a GUI. */
    public Optional<NamespacedId> gui(String alias) {
        return Optional.ofNullable(bindings.get(alias))
                .filter(binding -> binding.kind() == OpenDeclaration.Kind.GUI)
                .map(Binding::id);
    }

    /** Runs a declared command: the Brigadier executor for every prefix of its arguments. */
    public int run(String alias, CommandContext<CommandSourceStack> context) {
        Binding binding = bindings.get(alias);
        if (binding == null) {
            return 0;
        }
        return binding.kind() == OpenDeclaration.Kind.COMMAND ? runDocument(alias, binding.id(), context)
                : runGui(alias, context);
    }

    /** A command document's name: hop to the sender's thread, then the documents' own gate and flow. */
    private int runDocument(String alias, NamespacedId command, CommandContext<CommandSourceStack> context) {
        Optional<OpenPlan> plan = documents.plan(command);
        if (plan.isEmpty()) {
            return 0;
        }
        Map<String, String> raw = rawArguments(context, plan.get());
        CommandSender sender = context.getSource().getSender();
        Runnable task = () -> documents.run(command, alias, sender, raw);
        if (sender instanceof Player player) {
            scheduling.executeEntity(plugin, player, task);
        } else {
            scheduling.executeGlobal(plugin, task);
        }
        return 1;
    }

    private int runGui(String alias, CommandContext<CommandSourceStack> context) {
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
            Optional<OpenArgSpec> arg = planOf(alias).flatMap(plan -> plan.arg(argument));
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

    /** The live plan behind an alias: a GUI's {@code open:} block or a command document's keys. */
    private Optional<OpenPlan> planOf(String alias) {
        Binding binding = bindings.get(alias);
        if (binding == null) {
            return Optional.empty();
        }
        if (binding.kind() == OpenDeclaration.Kind.COMMAND) {
            return documents.plan(binding.id());
        }
        return live.get().gui(binding.id()).map(RuntimeGuis.Live::open);
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
