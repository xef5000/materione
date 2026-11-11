package dev.materione.plugin.escrow.gate;

import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * The production-side crash gate of the kill-boundary integration test (gui-input-durability.md §8, row H of #247): a
 * <em>named</em> point in the escrow code where, only under a test, the calling thread stops for good so that a harness
 * can {@code kill -9} the server while it stands exactly there.
 *
 * <p><b>Inert in production, by construction.</b> The gate exists only when the JVM was started with
 * {@code -Dmaterione.escrow.gate=<name>}: the property is read <em>once</em>, at class initialisation, into a
 * {@code static final} flag, so on a server without it {@link #at} is one load of a constant {@code false} that the JIT
 * folds away. With the property set, the <em>current</em> value of the property is the name of the armed gate, so the
 * harness (which cannot change a JVM argument between two steps of one boot) re-arms it from inside the server with
 * {@code System.setProperty}; a value that names no gate ({@code off}) arms nothing. Nothing reads any other input,
 * nothing here can be reached from a command, a config file or an editor, and it never alters what a step does: it only
 * stops after the step's own work for the point it names.
 *
 * <p>When the armed name is reached the gate logs one line, {@value #MARKER}{@code <name>}, and parks the calling
 * thread forever. Nothing ticks after that (the call sites are on the owner thread, which is the main thread on
 * Paper), so no autosave can run behind the harness's back, and the harness kills the process tree on seeing the
 * line. The gate names are listed in {@link Gates}.
 *
 * <p>It lives in its own package, outside {@code escrow.runtime}, because the owner side may not block (I4); the
 * ban test {@code CrashGateBansTest} keeps it the only parking site of the escrow code.
 */
public final class CrashGate {

    /** The system property that creates the gate and, as it changes, names the armed point. */
    public static final String PROPERTY = "materione.escrow.gate";

    /** The log prefix the harness waits for. */
    public static final String MARKER = "MATERIONE-ESCROW-GATE ";

    /** The names of the gate points, in the order a player's session meets them. */
    public interface Gates {
        /** A tier-1 step wrote the account into the player's data; the live stacks have not changed yet. */
        String ACCOUNT_WRITTEN = "account-written";
        /** A manual click's step ended: the account and the live stacks both changed. */
        String CLICK_APPLIED = "click-applied";
        /** A window was released (close, quit, replaced): the stacks are in the inventory or OWED. */
        String RELEASE_DONE = "release-done";
        /** Recovery on join computed its result and has not written it or opened the gate. */
        String RECOVERY_BEFORE_COMMIT = "recovery-before-commit";
        /** Recovery finished: the account is written and the gate is open. */
        String RECOVERY_DONE = "recovery-done";
        /** {@code PlayerDeathEvent}: the release step ran, the drop list is not yet fixed. */
        String DEATH_RELEASED = "death-released";
        /** {@code /materione claim} ended. */
        String CLAIM_DONE = "claim-done";
    }

    private static final boolean ENABLED = System.getProperty(PROPERTY) != null;

    private static final CrashGate INSTANCE = new CrashGate(() -> System.getProperty(PROPERTY),
            line -> Logger.getLogger("Materione").info(line), CrashGate::parkForever);

    private final Supplier<String> armed;
    private final Consumer<String> log;
    private final Runnable park;

    /** A gate over its three inputs, for a test; production has the one {@link #at} uses. */
    CrashGate(Supplier<String> armed, Consumer<String> log, Runnable park) {
        this.armed = armed;
        this.log = log;
        this.park = park;
    }

    /**
     * Reaches the gate point {@code name}: returns at once unless the JVM was started with the gate property and
     * its current value is {@code name}, in which case it never returns.
     */
    public static void at(String name) {
        if (ENABLED) {
            INSTANCE.reach(name);
        }
    }

    /** Whether the JVM was started with the gate property at all (false on every production server). */
    public static boolean enabled() {
        return ENABLED;
    }

    /** The decision, separated from the static flag so a test can drive it. */
    void reach(String name) {
        if (name.equals(armed.get())) {
            log.accept(MARKER + name);
            park.run();
        }
    }

    private static void parkForever() {
        while (true) {
            LockSupport.park();
        }
    }
}
