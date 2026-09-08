package dev.materione.plugin.command;

import java.util.List;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

/**
 * Registers {@code /materione} through the server's command map — the
 * paper-plugin equivalent of the old plugin.yml {@code commands:} block,
 * which Paper plugins ignore. Delegates to the same
 * {@link MaterioneCommand} executor/tab-completer pair that served the
 * Bukkit declaration, so every subcommand and completion behaves
 * identically; permissions keep their in-code checks and are declared with
 * OP defaults by the plugin at enable.
 */
public final class MaterioneCommandAdapter extends Command {

    private final MaterioneCommand executor;

    public MaterioneCommandAdapter(MaterioneCommand executor) {
        super("materione",
                "Materione root command (version, doctor, reload, dev, give, browser, editor)",
                "/materione <version|doctor|reload|dev|give|browser|editor>",
                List.of());
        this.executor = executor;
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        return executor.onCommand(sender, this, label, args);
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        return executor.onTabComplete(sender, this, alias, args);
    }
}
