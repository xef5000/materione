package dev.materione.plugin.view.open;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.LogicContext;
import dev.materione.behavior.rule.Rule;
import dev.materione.behavior.run.RunOwner;
import dev.materione.plugin.commands.ArgParser;
import dev.materione.plugin.commands.OpenPlan;
import dev.materione.plugin.commands.OpenUsage;
import dev.materione.plugin.commands.PlayerNames;
import dev.materione.plugin.gui.Viewer;
import dev.materione.plugin.logic.variable.VariableService;
import java.util.Map;
import java.util.Objects;

/**
 * The open specification's gate (logic.md §6.1, stages 2 to 4): the {@code open.permission}, the typed
 * arguments, and the {@code open.requirement} with its {@code deny} steps. It answers before any viewer record or
 * window exists, so a refusal costs nothing. A view with no {@code open:} block is admitted as it always was, with
 * its arguments as given.
 *
 * <p>One gate for every opened kind (dialogs.md §7.1, row G): it reads the kind from the {@link Subject} for the
 * usage line and the bypass node, and builds the context a requirement and the deny steps run against through an
 * {@link OpenContexts} the kind supplies, so a GUI and a dialog differ only in that one seam.
 *
 * <p>The bypass node {@code materione.<kind>.bypass.<pack>.<id>} skips the requirement only: it does not skip
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

    /**
     * Builds the context a view's open requirement and its deny steps run against (logic.md §6.1): there is no
     * viewer record yet, so the session variables and pages are empty, and the arguments are the typed values the
     * gate passes. Each kind supplies its own, which is the one thing about opening a kind that the gate does not
     * know.
     */
    @FunctionalInterface
    public interface OpenContexts {

        LogicContext open(Viewer viewer, NamespacedId id, Map<String, ?> typedArgs, VariableService variables);
    }

    private final Permissions permissions;
    private final PlayerNames players;
    private final DenyRunner denies;
    private final VariableService variables;
    private final OpenContexts contexts;

    public OpenSpecGate(
            Permissions permissions, PlayerNames players, DenyRunner denies, VariableService variables,
            OpenContexts contexts) {
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.players = players;
        this.denies = denies;
        this.variables = variables;
        this.contexts = Objects.requireNonNull(contexts, "contexts");
    }

    @Override
    public Admission admit(Viewer viewer, Subject subject, Map<String, String> args) {
        OpenPlan plan = subject.plan();
        if (plan == null) {
            return Admission.allow(args);
        }
        if (plan.permission() != null && !permissions.has(viewer, plan.permission())) {
            return Admission.deny("no permission (" + plan.permission() + ")");
        }
        ArgParser.Result parsed = ArgParser.parse(plan, args, players);
        if (!parsed.ok()) {
            return Admission.usage(OpenUsage.refusal(subject.command(), plan, parsed.error()));
        }
        if (plan.hasRequirement() && !permissions.has(viewer, subject.bypassNode())) {
            LogicContext context = contexts.open(
                    viewer, subject.id(), ArgParser.typed(plan, parsed.values()), variables);
            if (!plan.requirementMet(context)) {
                if (plan.deny() != null) {
                    denies.run(plan.deny(), context, RunOwner.of(viewer.id().toString(), viewer.player()));
                }
                return Admission.deny("the requirement for " + subject.id() + " is not met");
            }
        }
        return Admission.allow(parsed.values());
    }
}
