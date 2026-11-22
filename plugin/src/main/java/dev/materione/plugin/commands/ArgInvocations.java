package dev.materione.plugin.commands;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.entity.Player;

/**
 * What one typed command stands for (logic.md §40, #467): the arguments the client sent, as the runs the command makes.
 * Almost always that is one run with each argument's text. A {@code player} argument declared {@code selector: true} may
 * instead carry a vanilla selector ({@code @a}, {@code @p}, {@code @e[type=player,distance=..5]}); the selector is
 * resolved here, with the sender's own position and permission (Paper refuses a selector the sender may not use), and a
 * selector that matches several players makes several runs, one per player, in the order the server lists the matches.
 *
 * <p>The runs are capped at {@link #MAX_RUNS} in all, as {@code nearby} (§39) caps its limit at 16: past it the rest are
 * dropped and the expansion says so, so a pack cannot turn one typed command into a flood. Each selector argument
 * contributes its matches in turn; with two of them the runs are their combinations, up to the cap.
 */
final class ArgInvocations {

    /** The most runs one typed command makes. */
    static final int MAX_RUNS = 16;

    private ArgInvocations() {}

    /**
     * The runs of a typed command.
     *
     * @param runs the arguments of each run, by name (an argument the tree did not carry is absent); empty when
     *     {@code error} is set
     * @param error why the command cannot run (a selector that failed or matched nobody), or null
     * @param capped whether the matches were cut at {@link #MAX_RUNS}
     * @param wanted how many runs it would have been, when {@code capped}
     */
    record Expansion(List<Map<String, String>> runs, String error, boolean capped, int wanted) {

        static Expansion failed(String error) {
            return new Expansion(List.of(), error, false, 0);
        }
    }

    /** Reads what the client sent and expands the selectors. Never throws. */
    static Expansion of(CommandContext<CommandSourceStack> context, OpenPlan plan) {
        var values = new LinkedHashMap<String, List<String>>();
        for (OpenArgSpec arg : plan.args()) {
            ParsedCommandNode<CommandSourceStack> sent = sent(context, arg.name());
            if (sent == null) {
                continue;
            }
            if (!arg.selecting()) {
                values.put(arg.name(), List.of(String.valueOf(context.getArgument(arg.name(), Object.class))));
                continue;
            }
            String text = sent.getRange().get(context.getInput());
            if (!text.startsWith("@")) {
                values.put(arg.name(), List.of(text)); // a plain name: the argument's own parse (online, or offline)
                continue;
            }
            try {
                List<Player> matched = context.getArgument(arg.name(), PlayerSelectorArgumentResolver.class)
                        .resolve(context.getSource());
                if (matched.isEmpty()) {
                    return Expansion.failed("no player matches " + text);
                }
                values.put(arg.name(), matched.stream().map(Player::getName).toList());
            } catch (CommandSyntaxException refused) {
                return Expansion.failed(refused.getRawMessage().getString());
            } catch (RuntimeException broken) {
                return Expansion.failed("the selector " + text + " could not be resolved: " + broken.getMessage());
            }
        }
        return product(values);
    }

    /** The combinations of the values, in argument order and match order, cut at {@link #MAX_RUNS}. Pure. */
    static Expansion product(Map<String, List<String>> values) {
        long wanted = 1;
        for (List<String> each : values.values()) {
            wanted = Math.min(Long.MAX_VALUE / 2, wanted * each.size());
        }
        var runs = new ArrayList<Map<String, String>>();
        runs.add(new LinkedHashMap<>());
        for (Map.Entry<String, List<String>> argument : values.entrySet()) {
            var next = new ArrayList<Map<String, String>>();
            for (Map<String, String> partial : runs) {
                for (String value : argument.getValue()) {
                    var run = new LinkedHashMap<>(partial);
                    run.put(argument.getKey(), value);
                    next.add(run);
                    if (next.size() >= MAX_RUNS) {
                        break;
                    }
                }
                if (next.size() >= MAX_RUNS) {
                    break;
                }
            }
            runs = next;
        }
        return new Expansion(List.copyOf(runs), null, wanted > MAX_RUNS, (int) Math.min(Integer.MAX_VALUE, wanted));
    }

    private static ParsedCommandNode<CommandSourceStack> sent(CommandContext<CommandSourceStack> context, String name) {
        for (ParsedCommandNode<CommandSourceStack> parsed : context.getNodes()) {
            if (parsed.getNode() instanceof ArgumentCommandNode<?, ?> node && node.getName().equals(name)) {
                return parsed;
            }
        }
        return null;
    }
}
