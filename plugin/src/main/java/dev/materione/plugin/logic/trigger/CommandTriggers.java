package dev.materione.plugin.logic.trigger;

import dev.materione.api.logic.ContextVariable;
import dev.materione.api.logic.TriggerDescription;
import dev.materione.api.logic.TriggerType;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.content.commands.CommandType;
import java.util.List;

/**
 * The trigger a command document's {@code do:} runs under (logic.md §40). Like every trigger it is a description:
 * who fires it, what its context guarantees. Registered through the public logic API, so the compiler checks
 * {@code ${root}} paths against this schema, the editor autocompletes from it and the code-mode SDK reads it (D5). The
 * firing site is the command's executor ({@code CommandRuns}).
 *
 * <p>The trigger is fired by the {@code command} host only: it carries the sender and the typed arguments, and no
 * rule document or item answers it (a rule's {@code on:} list never offers it).
 */
public final class CommandTriggers {

    /** The trigger id, the one {@link CommandType#TRIGGER} names. */
    public static final String RUN = CommandType.TRIGGER;

    private CommandTriggers() {}

    /** The one trigger a command document runs under. */
    public static List<TriggerType> all() {
        var context = List.of(
                ContextVariable.of("player.name", ParameterType.STRING,
                        "The sender's name; the console's name when the console runs the command"),
                ContextVariable.of("player.uuid", ParameterType.STRING, "The sender's UUID; absent for the console"),
                ContextVariable.of("player.gamemode", ParameterType.STRING,
                        "survival, creative, adventure or spectator; absent for the console"),
                ContextVariable.of("player.sneaking", ParameterType.BOOLEAN, "Whether the sender is sneaking"),
                ContextVariable.of("player.op", ParameterType.BOOLEAN, "Whether the sender is an operator"),
                ContextVariable.of("player.health", ParameterType.DECIMAL, "The sender's health"),
                ContextVariable.of("player.level", ParameterType.INTEGER, "The sender's experience level"),
                ContextVariable.of("player.x", ParameterType.DECIMAL, "The sender's X coordinate"),
                ContextVariable.of("player.y", ParameterType.DECIMAL, "The sender's Y coordinate"),
                ContextVariable.of("player.z", ParameterType.DECIMAL, "The sender's Z coordinate"),
                ContextVariable.of("location.x", ParameterType.DECIMAL, "X of the sender"),
                ContextVariable.of("location.y", ParameterType.DECIMAL, "Y of the sender"),
                ContextVariable.of("location.z", ParameterType.DECIMAL, "Z of the sender"),
                ContextVariable.of("location.world", ParameterType.STRING, "World name of the sender"),
                ContextVariable.of("world.name", ParameterType.STRING, "World name"),
                ContextVariable.of("world.time", ParameterType.INTEGER, "World time in ticks"),
                ContextVariable.of("server.online", ParameterType.INTEGER, "Players online"),
                ContextVariable.of("server.max", ParameterType.INTEGER, "Player slots"),
                ContextVariable.of("args.*", ParameterType.RAW,
                        "The typed arguments the sender gave, by name: whole numbers and numbers as numbers, true and "
                                + "false as booleans, everything else as text; an optional argument left out and "
                                + "with no default is absent"),
                ContextVariable.of("command.name", ParameterType.STRING, "The command's first name"),
                ContextVariable.of("command.label", ParameterType.STRING, "The name the sender typed (an alias, maybe)"),
                ContextVariable.of("vars.*", ParameterType.RAW, "Variables written earlier in this run by set_var"));
        return List.of(new CommandTrigger(RUN, new TriggerDescription(
                SelfDescription.of("Command run",
                        "A sender runs a command a command document declares, after its permission, arguments and "
                                + "requirement are checked"),
                List.of(), List.of(CommandType.NAME), List.of(), context, false)));
    }

    /** A trigger that is only its description. */
    private record CommandTrigger(String id, TriggerDescription description) implements TriggerType {
        @Override
        public TriggerDescription describe() {
            return description;
        }
    }
}
