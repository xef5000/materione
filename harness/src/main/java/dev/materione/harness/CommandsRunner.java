package dev.materione.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Commands as content on a real Paper server (logic.md §40, #330): the names a command document declares reach the
 * server's command tree at boot with their typed arguments, a player runs them as a player types them, and the gate
 * refuses in the order the design fixes.
 *
 * <p>The players are the fake-connection players of {@link CrashRig}, and a player's command is the probe's
 * {@code dprobe cmd} ({@code Player#performCommand}, the path a typed command takes); the console runs a command as the
 * console does. The commands are the {@code commands} fixture pack: each announces itself with a
 * {@code say MATERIONE-COMMANDS-...} line, and the executor logs its own {@code MATERIONE-COMMAND alias=... outcome=...}
 * line for what a refusal is. Completions are read from the tree through Paper's own tab-complete path
 * ({@code dprobe tab}). What a player sees is the QA catalog's {@code commands} suite.
 *
 * <p>This proves the logic on Paper, where every thread check is true; nothing here ran on Folia.
 */
public final class CommandsRunner {

    private static final String M = "MATERIONE-COMMANDS-";
    private static final String OUT = "MATERIONE-COMMAND alias=";

    public HarnessResult run(HarnessOptions options) {
        List<String> problems = new ArrayList<>();
        CrashRig rig = null;
        try {
            rig = CrashRig.createForEscrow(options, "commands", null, Set.of("commands"));
            rig.boot();
            scenario(rig, problems);
            rig.stop();
            String log = rig.log().readAll();
            if (log == null) {
                problems.add("no server log");
            } else {
                if (!log.contains("MATERIONE-OPEN-TREE declared=8 registered=8")) {
                    problems.add("the COMMANDS event at boot did not register the eight declared names from the built "
                            + "registries: " + firstLineWith(log, "MATERIONE-OPEN-TREE"));
                }
                if (log.contains("MATERIONE-OPEN-TREE reload")) {
                    problems.add("a boot with unchanged content asked for a data reload: "
                            + firstLineWith(log, "MATERIONE-OPEN-TREE reload"));
                }
                for (String code : List.of("MTR-0021", "MTR-0057", "MTR-0060", "MTR-0066", "MTR-0067", "MTR-0068",
                        "MTR-0069", "behavior bridge failed")) {
                    if (log.contains(code)) {
                        problems.add("a diagnostic was logged (" + code + "): " + firstLineWith(log, code));
                    }
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
        rig.probe("join", "Alice");
        rig.probe("join", "Bob");

        // T27: a command with no argument and no gate runs, and its context says who and by which name
        expect(rig, problems, "/discord runs for a player", "dprobe cmd Alice discord", M + "DISCORD Alice label discord");

        // the permission is checked first: Alice is not an operator, so /staff refuses her before it does anything
        expect(rig, problems, "a player without the permission is refused", "dprobe cmd Alice staff",
                OUT + "staff command=commands:staff outcome=denied");

        // typed arguments reach args.*: the number is a number (doubled is 10), the player is the online player's own
        // spelling, and the name and the label are what they are
        expect(rig, problems, "typed arguments reach args.*", "dprobe cmd Alice pay Bob 5",
                M + "PAY Alice to Bob amount 5 doubled 10 label pay name pay");
        expect(rig, problems, "an alias is the same command under its own label", "dprobe cmd Alice give-coins bob 2",
                M + "PAY Alice to Bob amount 2 doubled 4 label give-coins name pay");

        // a player who is not online is a usage refusal from the server (the client's tree takes any word)
        expect(rig, problems, "an unknown player is a usage refusal", "dprobe cmd Alice pay Nobody 5",
                OUT + "pay command=commands:pay outcome=usage");
        // the requirement refuses a non-positive amount before the flow, and the deny steps are the answer
        expect(rig, problems, "a failed requirement runs the deny steps", "dprobe cmd Alice pay Bob -3",
                M + "PAY-DENIED Alice amount -3");
        // an operator holds every node nobody registered, the bypass node among them: the requirement is skipped, and
        // the permission she lacked is there
        rig.command("op Alice", "Made Alice a server operator");
        expect(rig, problems, "the bypass node skips the requirement", "dprobe cmd Alice pay Bob -3",
                M + "PAY Alice to Bob amount -3 doubled -6 label pay name pay");
        expect(rig, problems, "and an operator holds the permission", "dprobe cmd Alice staff", M + "STAFF Alice");
        rig.command("deop Alice", "Made Alice no longer a server operator");

        // optional, boolean, choice and int arguments, their defaults, and a flow that reads the command's args
        expect(rig, problems, "defaults fill in", "dprobe cmd Alice tune", M + "TUNE Alice mode soft loud");
        expect(rig, problems, "given arguments override them", "dprobe cmd Alice tune off true 3",
                M + "TUNE Alice mode off loud true times 3");
        expect(rig, problems, "a value a choice does not allow is a usage refusal", "dprobe cmd Alice tune bogus",
                OUT + "tune command=commands:tune outcome=usage");

        // the console: refused unless the document says console: true, and then it has no player
        expect(rig, problems, "the console is refused a command with no console form", "pay Bob 5",
                OUT + "pay command=commands:pay outcome=console");
        expect(rig, problems, "the console runs a console command", "announce", M + "ANNOUNCE CONSOLE");

        // completions come from the same tree: the online players, the choice's values, true and false
        completions(rig, problems, "a player argument offers the online players", "pay <>", "[Alice, Bob]");
        completions(rig, problems, "a player argument narrows by what was typed", "pay B", "[Bob]");
        completions(rig, problems, "a choice offers its values", "tune <>", "[loud, off, soft]");
        completions(rig, problems, "a boolean offers true and false", "tune off <>", "[false, true]");

        // open: is the sugar of a command: the GUI's alias and the document that opens the same GUI both run it
        expect(rig, problems, "a GUI's open: alias opens its GUI", "dprobe cmd Alice cmenu 2",
                M + "MENU-OPEN Alice page 2");
        expect(rig, problems, "a document that opens the GUI is the same command written out", "dprobe cmd Alice menu2 3",
                M + "MENU-OPEN Alice page 3");
    }

    /** Asks the tree what it completes for {@code buffer} and checks the sorted list the probe prints. */
    private static void completions(CrashRig rig, List<String> problems, String what, String buffer, String expected)
            throws Exception {
        try {
            String line = rig.command("dprobe tab Alice " + buffer, "MATERIONE-DPROBE tab ");
            if (!line.contains("completions=" + expected)) {
                problems.add(what + ": expected completions=" + expected + " but the probe said: " + line);
            }
        } catch (IllegalStateException missing) {
            problems.add(what + ": " + missing.getMessage().lines().findFirst().orElse(""));
        }
    }

    /** Sends {@code command} and checks the marker arrives. */
    private static void expect(CrashRig rig, List<String> problems, String what, String command, String marker)
            throws Exception {
        try {
            rig.command(command, marker);
        } catch (IllegalStateException missing) {
            problems.add(what + ": " + missing.getMessage().lines().findFirst().orElse(""));
        }
    }

    private static String firstLineWith(String log, String fragment) {
        return log.lines().filter(line -> line.contains(fragment)).findFirst().orElse("");
    }
}
