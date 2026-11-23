package dev.materione.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * {@code view.refresh} on a real Paper server (views.md sections 3.6 and 5.4). The player is the
 * fake-connection player of {@link CrashRig} (a real {@code ServerPlayer}, no socket); its clicks are real container-click
 * packets handled by its own packet listener, so a click runs the GUI's rules on the server thread as it does for a client.
 *
 * <p>The window is the {@code guilogic:manual} fixture: it says {@code rerender: off}, shows an emerald while the server
 * variable {@code guilogic:wave} is {@code storm} (a notified read) and a diamond while the player has five levels (a polled
 * fact). It has three levers, one per selector. The scenario proves, in order: nothing re-renders the window when what it reads
 * changes (it said {@code off}); a {@code var:} selection leaves it alone for the same reason (it is not in the reads index); a
 * {@code view:} selection and the no-selector form each bring both items in; and the same two bring both out again.
 * Headless: the window is read back with the probe's {@code inv}, which lists the materials in the open top inventory.
 */
public final class ViewRefreshRunner {

    private static final String PLAYER = "Alice";
    private static final String OTHER = "Bob";
    private static final String GUI = "guilogic:manual";
    private static final int SURGE_SLOT = 14;
    private static final int SURGE_LEVER = 17;
    private static final int STORM_SLOT = 12;
    private static final int LEVELS_SLOT = 13;
    private static final int OWN_LEVER = 15;
    private static final int VIEW_LEVER = 16;
    private static final int VAR_LEVER = 17;

    public HarnessResult run(HarnessOptions options) {
        List<String> problems = new ArrayList<>();
        CrashRig rig = null;
        try {
            rig = CrashRig.createForEscrow(options, "view-refresh", null, Set.of("guilogic"));
            rig.boot();
            scenario(rig, problems);
            rig.stop();
            String log = rig.log().readAll();
            for (String fragment : List.of("behavior bridge failed", "world event bridge failed", "guilogic:manual: MTR-")) {
                if (log != null && log.contains(fragment)) {
                    problems.add("the log carries '" + fragment + "': "
                            + log.lines().filter(line -> line.contains(fragment)).findFirst().orElse(""));
                }
            }
        } catch (Exception e) {
            problems.add("scenario error: " + e);
        } finally {
            if (rig != null) {
                rig.close();
            }
        }
        return new HarnessResult(problems.isEmpty(), problems, -1, rig == null ? null : rig.workspace().root,
                rig == null ? List.of() : rig.tailLines());
    }

    private static void scenario(CrashRig rig, List<String> problems) throws Exception {
        rig.probe("join", PLAYER);
        rig.command("materione gui open " + GUI + " " + PLAYER, "player=" + PLAYER + " container=");
        expectWindow(rig, problems, PLAYER, "a fresh window shows neither item", false, false);

        // what the window reads changes; it said rerender: off, so nothing re-renders it
        rig.command("xp set " + PLAYER + " 7 levels", "Set");
        rig.command("materione var set - guilogic:wave storm", "guilogic:wave");
        settle();
        expectWindow(rig, problems, PLAYER, "a window that says rerender: off is not re-rendered by a write", false, false);

        // var: selects the viewers whose view reads the variable; this view is not in the index, so it selects nobody
        click(rig, VAR_LEVER);
        expectWindow(rig, problems, PLAYER, "a var: selection honours rerender: off and leaves the window alone", false, false);

        // view: names the definition: every viewer of it renders, whatever the index says
        click(rig, VIEW_LEVER);
        expectWindow(rig, problems, PLAYER, "a view: selection renders the window (both items appear)", true, true);

        // and back: the facts change, the same selector shows it
        rig.command("xp set " + PLAYER + " 0 levels", "Set");
        rig.command("materione var set - guilogic:wave calm", "guilogic:wave");
        settle();
        expectWindow(rig, problems, PLAYER, "the window keeps what it showed until asked", true, true);
        click(rig, VIEW_LEVER);
        expectWindow(rig, problems, PLAYER, "a view: selection renders the window again (both items go)", false, false);

        // no selector: the run's own window, in full
        rig.command("xp set " + PLAYER + " 9 levels", "Set");
        rig.command("materione var set - guilogic:wave storm", "guilogic:wave");
        settle();
        click(rig, OWN_LEVER);
        expectWindow(rig, problems, PLAYER, "no selector renders the run's own window (both items appear)", true, true);

        twoWindows(rig, problems);
    }

    /**
     * Two players have {@code guilogic:live} open; one clicks the lever that writes the server variable, and both windows
     * show the emerald it names, with no {@code view.refresh} in the rules (acceptance case 2 on a running server).
     */
    private static void twoWindows(CrashRig rig, List<String> problems) throws Exception {
        rig.command("materione var set - guilogic:wave calm", "guilogic:wave");
        rig.probe("join", OTHER);
        for (String player : List.of(PLAYER, OTHER)) {
            rig.command("materione gui open guilogic:live " + player, "player=" + player + " container=");
        }
        settle();
        for (String player : List.of(PLAYER, OTHER)) {
            expectSurge(rig, problems, player, "before the lever, " + player + "'s window shows no emerald", false);
        }
        click(rig, PLAYER, SURGE_LEVER);
        for (String player : List.of(PLAYER, OTHER)) {
            expectSurge(rig, problems, player, "after one player's click, " + player + "'s window shows the emerald", true);
        }
    }

    private static void click(CrashRig rig, int slot) throws Exception {
        click(rig, PLAYER, slot);
    }

    private static void click(CrashRig rig, String player, int slot) throws Exception {
        rig.probe("click", player + " " + slot + " 0 PICKUP");
        settle();
    }

    /** A click's render is deferred by a tick when it would meet the click that caused it; a short wait covers it. */
    private static void settle() throws InterruptedException {
        Thread.sleep(600);
    }

    private static void expectWindow(CrashRig rig, List<String> problems, String player, String what, boolean storm, boolean levels)
            throws Exception {
        String window = windowOf(rig, player);
        if (window.isEmpty()) {
            problems.add(what + ": the player has no window");
            return;
        }
        boolean hasStorm = window.contains(STORM_SLOT + ":EMERALD");
        boolean hasLevels = window.contains(LEVELS_SLOT + ":DIAMOND");
        if (hasStorm != storm || hasLevels != levels) {
            problems.add(what + ": expected emerald=" + storm + " diamond=" + levels + ", the window is " + window);
        }
    }

    private static void expectSurge(CrashRig rig, List<String> problems, String player, String what, boolean surging)
            throws Exception {
        String window = windowOf(rig, player);
        if (window.contains(SURGE_SLOT + ":EMERALD") != surging) {
            problems.add(what + ": expected emerald=" + surging + ", the window is '" + window + "'");
        }
    }

    /** The materials in the player's open top inventory as the probe lists them ({@code slot:MATERIAL*n,...}), or empty. */
    private static String windowOf(CrashRig rig, String player) throws Exception {
        String line = rig.probe("inv", player);
        int from = line.indexOf("window=[");
        return from < 0 ? "" : line.substring(from, line.indexOf(']', from));
    }
}
