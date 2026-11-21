package dev.materione.content.gui.logic;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.gui.GuiAuthoringApi.OpenSpec;
import dev.materione.content.logic.CommandSchema;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@code open:} block's shape (gui.md §6.1, logic.md §6.1): read from a GUI's authored mapping
 * into a typed {@link OpenSpec}, checked, and written back in the authored key order. The runtime
 * that acts on it is #215; this class only knows what a valid block looks like. A condition and the
 * steps of {@code deny} are checked for their outer shape here (a condition is a string, a mapping or
 * a list; {@code deny} is a list of steps); their compile is the logic compiler's, reached through the
 * GUI's logic check.
 *
 * <p>Since #330 the shape is {@link CommandSchema}'s, which a {@code command} document shares (logic.md §40): this
 * class is the {@code open:} spelling of it ({@link CommandSchema.Dialect#OPEN}), and nothing in it is a second copy.
 */
public final class GuiOpen {

    /** The argument types the runtime parses (logic.md §6.1). */
    public static final Set<String> ARG_TYPES = CommandSchema.ARG_TYPES;

    /** A read block: the typed spec and the problems found in its authored shape. */
    public record Parsed(OpenSpec spec, List<Diagnostic> problems) {}

    private GuiOpen() {}

    /**
     * Reads the authored {@code open:} value. A value that is not a mapping yields no spec and one
     * problem; a malformed part yields the spec with what could be read and the problems named.
     */
    public static Parsed read(Object raw) {
        CommandSchema.Parsed parsed = CommandSchema.read(raw, CommandSchema.Dialect.OPEN);
        return new Parsed(parsed.spec(), parsed.problems());
    }

    /** The problems in a spec, for a write: the same checks {@link #read} makes, on the typed form. */
    public static List<Diagnostic> check(OpenSpec spec) {
        return CommandSchema.check(spec, CommandSchema.Dialect.OPEN);
    }

    /** The authored mapping of a spec, in the order the reference shows (commands, args, permission, requirement, deny). */
    public static Map<String, Object> write(OpenSpec spec) {
        return CommandSchema.write(spec, CommandSchema.Dialect.OPEN);
    }
}
