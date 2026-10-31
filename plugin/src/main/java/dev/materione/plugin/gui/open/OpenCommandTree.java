package dev.materione.plugin.gui.open;

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
import java.util.List;
import java.util.function.Predicate;

/**
 * Builds the Brigadier literal for one open command (logic.md §6.1). The arguments are typed in the client's
 * tree, so the client validates them as it types and offers completions (issue #96: a typed argument has to
 * reach the client's synced tree, or it cannot be typed at all). The server still parses every value again
 * with the live block, so a value the client let through, or a raw packet, gets the usage line rather than a
 * crash.
 *
 * <p>Every prefix of the arguments is executable, so {@code /shop} and {@code /shop tools} both reach the
 * handler; the handler decides whether the arguments it has are enough. Activation is the {@code requires}
 * predicate: a command that is no longer declared stays in the tree (Brigadier cannot remove a child) but is
 * neither suggested nor run.
 */
final class OpenCommandTree {

    private OpenCommandTree() {}

    /**
     * The literal for {@code alias}.
     *
     * @param args the arguments of the GUI the alias opens, in order
     * @param active whether the alias is declared right now (the {@code requires} predicate)
     * @param run the executor, which every prefix of the arguments reaches
     * @param suggestions the completions for one argument, by its name
     */
    static LiteralCommandNode<CommandSourceStack> build(
            String alias, List<OpenArgSpec> args, Predicate<CommandSourceStack> active,
            Command<CommandSourceStack> run, java.util.function.Function<String, SuggestionProvider<CommandSourceStack>> suggestions) {
        ArgumentBuilder<CommandSourceStack, ?> child = null;
        for (int index = args.size() - 1; index >= 0; index--) {
            OpenArgSpec arg = args.get(index);
            RequiredArgumentBuilder<CommandSourceStack, ?> level = Commands.argument(arg.name(), argumentType(arg.type()))
                    .suggests(suggestions.apply(arg.name()))
                    .executes(run);
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

    /** The Brigadier type an argument of {@code type} takes. A player is a word the handler resolves by name. */
    static ArgumentType<?> argumentType(ArgType type) {
        return switch (type) {
            case INT -> IntegerArgumentType.integer();
            case NUMBER -> DoubleArgumentType.doubleArg();
            case BOOLEAN -> BoolArgumentType.bool();
            case PLAYER -> StringArgumentType.word();
            case STRING, CHOICE -> StringArgumentType.string();
        };
    }

    /**
     * The argument shape the tree was built with: each name and its Brigadier type, in order. A reload that
     * changes it cannot change a node that is already in the tree, so the registrar reports it.
     */
    static List<String> shape(List<OpenArgSpec> args) {
        return args.stream().map(arg -> arg.name() + ":" + argumentType(arg.type()).getClass().getSimpleName()).toList();
    }
}
