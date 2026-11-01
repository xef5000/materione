package dev.materione.plugin.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.List;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

/**
 * Registers {@code /materione} through Paper's COMMANDS lifecycle as a {@link BasicCommand}.
 * Its greedy-string tail accepts namespaced ids and multiple arguments in the synced client
 * tree (#96), while delegating to the same
 * {@link MaterioneCommand} executor/tab-completer pair that served the
 * Bukkit declaration, so every subcommand and completion behaves
 * identically; permissions keep their in-code checks and are declared with
 * OP defaults by the plugin at enable.
 */
public final class MaterioneCommandAdapter extends Command implements BasicCommand {

    private final CommandExecutor executor;
    private final TabCompleter completer;

    public MaterioneCommandAdapter(MaterioneCommand executor) {
        this(executor, executor);
    }

    MaterioneCommandAdapter(CommandExecutor executor, TabCompleter completer) {
        super("materione",
                "Materione root command (version, doctor, reload, dev, give, browser, editor)",
                "/materione <version|doctor|reload|dev|give|browser|editor>",
                List.of());
        this.executor = executor;
        this.completer = completer;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        execute(source.getSender(), "materione", args);
    }

    @Override
    public List<String> suggest(CommandSourceStack source, String[] args) {
        return tabComplete(source.getSender(), "materione", args);
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        return executor.onCommand(sender, this, label, args);
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        return completer.onTabComplete(sender, this, alias, args);
    }
}
