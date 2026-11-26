package dev.materione.plugin.view.open;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.variable.StackRef;
import dev.materione.plugin.commands.OpenPlan;
import dev.materione.plugin.gui.Viewer;
import java.util.Map;
import java.util.Objects;

/**
 * Stages 2 to 4 of opening (logic.md §6.1) seen from an opener: may this viewer open this view with these
 * arguments, and with which arguments exactly? The open specification ({@code open.permission}, typed
 * {@code open.args}, {@code open.requirement} and its deny flow) is implemented behind this seam; an opener only
 * asks, and opens with the arguments the gate returns (typed, defaulted and validated) or refuses with the gate's
 * reason.
 *
 * <p>The gate is shared by every opened view kind (dialogs.md §7.1, views.md §3.5): a GUI's opener and a dialog's
 * ask the same question of the same code, so the two cannot drift. It names what is being opened as a
 * {@link Subject}, not as a GUI, and knows nothing else about the kind.
 *
 * <p>Called on the viewer's thread, before any viewer record or window exists, so a refusal costs nothing.
 */
@FunctionalInterface
public interface OpenGate {

    /** Admits everything with the arguments as given: the gate until a view declares an {@code open:} block. */
    OpenGate ALLOW = (viewer, subject, args) -> Admission.allow(args);

    /**
     * What is being opened: the view's kind ({@code gui}, {@code dialog}), its id and its open specification.
     *
     * @param kind the view kind's content type name; it names the usage line and the bypass node
     * @param id the definition's id
     * @param plan the compiled {@code open:} block, or {@code null} when the definition declares none
     */
    record Subject(String kind, NamespacedId id, OpenPlan plan) {

        public Subject {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(id, "id");
        }

        /** {@code /materione <kind> open <id>}: the command prefix a usage refusal names. */
        public String command() {
            return "/materione " + kind + " open " + id;
        }

        /** {@code materione.<kind>.bypass.<pack>.<id>}: the node that skips this view's requirement. */
        public String bypassNode() {
            return OpenPlan.bypassNode(kind, id);
        }
    }

    Admission admit(Viewer viewer, Subject subject, Map<String, String> args);

    /**
     * Admits an open that may carry the subject stack of the triggering run. Gates that do not inspect a stack
     * keep the original contract.
     */
    default Admission admit(Viewer viewer, Subject subject, Map<String, String> args, StackRef stack) {
        return admit(viewer, subject, args);
    }

    /**
     * The gate's answer.
     *
     * @param allowed whether the open may go on
     * @param args the arguments the viewer record opens with (after typing and defaults), when allowed
     * @param reason why it was refused, when it was: the usage line for an argument that does not read
     * @param usage whether the refusal is an argument problem (a usage message) rather than a denial
     */
    record Admission(boolean allowed, Map<String, String> args, String reason, boolean usage) {

        public Admission {
            args = Map.copyOf(args);
            Objects.requireNonNull(reason, "reason");
        }

        public static Admission allow(Map<String, String> args) {
            return new Admission(true, args, "", false);
        }

        public static Admission deny(String reason) {
            return new Admission(false, Map.of(), reason, false);
        }

        /** A refusal because an argument does not read as its type: the reason is the usage line. */
        public static Admission usage(String reason) {
            return new Admission(false, Map.of(), reason, true);
        }
    }
}
