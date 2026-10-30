package dev.materione.plugin.gui.open;

import dev.materione.plugin.gui.RuntimeGuis;
import dev.materione.plugin.gui.Viewer;
import java.util.Map;
import java.util.Objects;

/**
 * Stages 2 to 4 of opening (logic.md §6.1) seen from the opener: may this viewer open this GUI with
 * these arguments, and with which arguments exactly? The open specification
 * ({@code open.permission}, typed {@code open.args}, {@code open.requirement} and its deny flow) is
 * implemented behind this seam; the opener only asks, and opens with the arguments the gate returns
 * (typed, defaulted and validated) or refuses with the gate's reason.
 *
 * <p>Called on the viewer's thread, before any session or window exists, so a refusal costs nothing.
 */
@FunctionalInterface
public interface OpenGate {

    /** Admits everything with the arguments as given: the gate until a GUI declares an {@code open:} block. */
    OpenGate ALLOW = (viewer, gui, args) -> Admission.allow(args);

    Admission admit(Viewer viewer, RuntimeGuis.Live gui, Map<String, String> args);

    /**
     * The gate's answer.
     *
     * @param allowed whether the open may go on
     * @param args the arguments the session opens with (after typing and defaults), when allowed
     * @param reason why it was refused, when it was
     */
    record Admission(boolean allowed, Map<String, String> args, String reason) {

        public Admission {
            args = Map.copyOf(args);
            Objects.requireNonNull(reason, "reason");
        }

        public static Admission allow(Map<String, String> args) {
            return new Admission(true, args, "");
        }

        public static Admission deny(String reason) {
            return new Admission(false, Map.of(), reason);
        }
    }
}
