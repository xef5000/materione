package dev.materione.plugin.gui.open;

import dev.materione.behavior.rule.Rule;
import dev.materione.behavior.run.RunOwner;
import dev.materione.api.logic.LogicContext;
import dev.materione.plugin.gui.RuntimeGuis;
import dev.materione.plugin.gui.Viewer;
import dev.materione.plugin.gui.event.GuiContexts;
import dev.materione.plugin.logic.variable.VariableService;
import java.util.Map;

/**
 * The open specification's gate (logic.md §6.1, stages 2 to 4): the {@code open.permission}, the typed
 * arguments, and the {@code open.requirement} with its {@code deny} steps. It answers before any session or
 * window exists, so a refusal costs nothing. A GUI with no {@code open:} block is admitted as it always was,
 * with its arguments as given.
 *
 * <p>The bypass node {@code materione.gui.bypass.<pack>.<id>} skips the requirement only: it does not skip
 * the permission or the argument types. The deny steps run through the flow engine as the player's run,
 * with the same variables a requirement reads, and the open is refused either way.
 */
public final class OpenSpecGate implements OpenGate {

    /** Whether a viewer holds a permission node. */
    @FunctionalInterface
    public interface Permissions {

        boolean has(Viewer viewer, String node);

        /** The live permission check on the viewer's player; false for a viewer with no player. */
        static Permissions live() {
            return (viewer, node) -> viewer.player() != null && viewer.player().hasPermission(node);
        }
    }

    /** Runs a rule's steps as a run of the viewer (the flow engine's interpreter, in production). */
    @FunctionalInterface
    public interface DenyRunner {

        void run(Rule rule, LogicContext context, RunOwner owner);
    }

    private final Permissions permissions;
    private final PlayerNames players;
    private final DenyRunner denies;
    private final VariableService variables;

    public OpenSpecGate(Permissions permissions, PlayerNames players, DenyRunner denies) {
        this(permissions, players, denies, null);
    }

    public OpenSpecGate(Permissions permissions, PlayerNames players, DenyRunner denies, VariableService variables) {
        this.permissions = permissions;
        this.players = players;
        this.denies = denies;
        this.variables = variables;
    }

    @Override
    public Admission admit(Viewer viewer, RuntimeGuis.Live gui, Map<String, String> args) {
        OpenPlan plan = gui.open();
        if (plan == null) {
            return Admission.allow(args);
        }
        if (plan.permission() != null && !permissions.has(viewer, plan.permission())) {
            return Admission.deny("no permission (" + plan.permission() + ")");
        }
        ArgParser.Result parsed = ArgParser.parse(plan, args, players);
        if (!parsed.ok()) {
            return Admission.usage(OpenUsage.refusal("/materione gui open " + gui.id(), plan, parsed.error()));
        }
        if (plan.hasRequirement() && !permissions.has(viewer, OpenPlan.bypassNode(gui.id()))) {
            LogicContext context = GuiContexts.open(viewer, gui.id(), parsed.values(), variables);
            if (!plan.requirementMet(context)) {
                if (plan.deny() != null) {
                    denies.run(plan.deny(), context, RunOwner.of(viewer.id().toString(), viewer.player()));
                }
                return Admission.deny("the requirement for " + gui.id() + " is not met");
            }
        }
        return Admission.allow(parsed.values());
    }
}
