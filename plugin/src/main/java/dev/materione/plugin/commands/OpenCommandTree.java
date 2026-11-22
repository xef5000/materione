package dev.materione.plugin.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import java.util.List;
import java.util.function.Predicate;

/**
 * Builds the Brigadier literal for one declared command (logic.md §6.1, §40). The arguments are typed in the client's
 * tree, so the client validates them as it types and offers completions (issue #96: a typed argument has to
 * reach the client's synced tree, or it cannot be typed at all). The server still parses every value again
 * with the live block, so a value the client let through, or a raw packet, gets the usage line rather than a
 * crash.
 *
 * <p>Every prefix of the arguments is executable, so {@code /shop} and {@code /shop tools} both reach the
 * handler; the handler decides whether the arguments it has are enough. Activation is the {@code requires}
 * predicate: a command that is no longer declared stays in the tree (Brigadier cannot remove a child) but is
 * neither suggested nor run, and with {@code visible: permitted} neither is it to a player who lacks its permission.
 */
final class OpenCommandTree {

    private OpenCommandTree() {}

    /**
     * The literal for {@code alias}.
     *
     * @param args the arguments of the command, in order
     * @param active whether the alias is declared right now and the source may see it (the {@code requires} predicate)
     * @param run the executor, which every prefix of the arguments reaches
     * @param suggestions the completions for one argument, by its name; null for an argument whose client type already
     *     completes it (a selector the player may type as it is)
     */
    static LiteralCommandNode<CommandSourceStack> build(
            String alias, List<OpenArgSpec> args, Predicate<CommandSourceStack> active,
            Command<CommandSourceStack> run, java.util.function.Function<String, SuggestionProvider<CommandSourceStack>> suggestions) {
        ArgumentBuilder<CommandSourceStack, ?> child = null;
        for (int index = args.size() - 1; index >= 0; index--) {
            OpenArgSpec arg = args.get(index);
            RequiredArgumentBuilder<CommandSourceStack, ?> level = Commands.argument(arg.name(), argumentType(arg))
                    .executes(run);
            if (!nativelyCompleted(arg)) {
                level.suggests(suggestions.apply(arg.name()));
            }
            if (child != null) {
                level.then(child);
            }
            child = level;
        }
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(alias).requires(active).executes(run);
        if (child != null) {
            root.then(child);
        }
        return root.build();
    }

    /**
     * Whether the client's own type for the argument completes it. A selector argument is the client's player selector
     * ({@code @p}, {@code @a[...]}, the online names), and replacing its completion with the server's would lose the
     * selector syntax, so it keeps it unless {@code offline} adds names the client cannot know.
     */
    static boolean nativelyCompleted(OpenArgSpec arg) {
        return arg.selecting() && !arg.offline() && arg.suggest().isEmpty();
    }

    /**
     * The Brigadier type an argument takes: bounds on the numbers (the client refuses an out-of-range value as it is
     * typed), the vanilla players selector for a {@code selector: true} player, and a word the handler resolves by name
     * for any other player.
     */
    static ArgumentType<?> argumentType(OpenArgSpec arg) {
        return switch (arg.type()) {
            case INT -> IntegerArgumentType.integer(
                    arg.min() == null ? Integer.MIN_VALUE : (int) Math.max(Integer.MIN_VALUE, arg.min()),
                    arg.max() == null ? Integer.MAX_VALUE : (int) Math.min(Integer.MAX_VALUE, arg.max()));
            case NUMBER -> DoubleArgumentType.doubleArg(
                    arg.min() == null ? -Double.MAX_VALUE : arg.min(), arg.max() == null ? Double.MAX_VALUE : arg.max());
            case BOOLEAN -> BoolArgumentType.bool();
            case PLAYER -> arg.selecting() ? ArgumentTypes.players() : StringArgumentType.word();
            case STRING, CHOICE -> StringArgumentType.string();
        };
    }

    /**
     * The argument shape the tree was built with: each name, its kind of Brigadier type and its bounds, in order. A
     * reload that changes it cannot change a node that is already in the tree, so the registrar reports it. It names the
     * type rather than building it, so it needs no server.
     */
    static List<String> shape(List<OpenArgSpec> args) {
        return args.stream().map(arg -> arg.name() + ":" + switch (arg.type()) {
            case INT -> "IntegerArgumentType" + range(arg);
            case NUMBER -> "DoubleArgumentType" + range(arg);
            case BOOLEAN -> "BoolArgumentType";
            case PLAYER -> arg.selecting() ? "PlayersArgumentType" : "StringArgumentType";
            case STRING, CHOICE -> "StringArgumentType";
        }).toList();
    }

    private static String range(OpenArgSpec arg) {
        return arg.min() == null && arg.max() == null ? "" : "[" + arg.min() + ".." + arg.max() + "]";
    }
}
