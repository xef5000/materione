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
                if (!log.contains("MATERIONE-OPEN-TREE declared=14 registered=14")) {
                    problems.add("the COMMANDS event at boot did not register the fourteen declared names from the built "
                            + "registries: " + firstLineWith(log, "MATERIONE-OPEN-TREE"));
                }
                // visible: permitted (the default) puts the permission in requires, so a player without it cannot even
                // reach the command: its gate never ran for her, which would have logged outcome=denied
                if (log.contains(OUT + "staff command=commands:staff outcome=denied")) {
                    problems.add("a hidden command reached its gate for a player who lacks the permission");
                }
                // @s, @a and a plain name: Alice was hugged by @s and @a, Bob by his name, @a and @a[name=Bob], and by
                // nothing else: one run per matched player, no more
                long alice = log.lines().filter(line -> line.contains(M + "HUG Alice hugs Alice")).count();
                long bob = log.lines().filter(line -> line.contains(M + "HUG Alice hugs Bob")).count();
                if (alice != 2 || bob != 3) {
                    problems.add("a selector did not run once per matched player: hugs Alice " + alice
                            + " (expected 2), hugs Bob " + bob + " (expected 3)");
                }
                // an out-of-range value is refused by the tree while it is parsed: the flow never ran
                if (log.contains(M + "GRANT Alice amount 500") || log.contains(M + "GRANT Alice amount 0.5")
                        || log.contains("times 9")) {
                    problems.add("a number outside its min and max ran the command: "
                            + firstLineWith(log, M + "GRANT"));
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

        // the permission is checked first. visible: always keeps the command in the list and refuses with the message
        expect(rig, problems, "visible: always refuses a player without the permission", "dprobe cmd Alice vip",
                OUT + "vip command=commands:vip outcome=denied");
        // visible: permitted (the default) hides it: it is not completed for her, and typing it reaches nothing
        commandList(rig, problems, "a command is hidden from a player who lacks its permission, and visible: always is not",
                "[vip]", "staff", "vip");
        rig.command("dprobe cmd Alice staff", "MATERIONE-DPROBE cmd ");

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
        commandList(rig, problems, "an operator is shown the hidden command too", "[staff, vip]", "staff", "vip");
        rig.command("deop Alice", "Made Alice no longer a server operator");

        // min and max: in range runs; outside it the tree refuses while the command is parsed (checked at the end: the
        // flow must never have run)
        expect(rig, problems, "a number inside its range runs", "dprobe cmd Alice grant 50 3",
                M + "GRANT Alice amount 50 times 3");
        expect(rig, problems, "the edge of the range runs", "dprobe cmd Alice grant 100",
                M + "GRANT Alice amount 100 times 1");
        rig.command("dprobe cmd Alice grant 500", "MATERIONE-DPROBE cmd ");
        rig.command("dprobe cmd Alice grant 0.5", "MATERIONE-DPROBE cmd ");
        rig.command("dprobe cmd Alice grant 5 9", "MATERIONE-DPROBE cmd ");

        // offline: a player who joined before and is no longer online is accepted, in their own spelling; without the
        // switch the same name is refused
        rig.probe("join", "Zed");
        rig.probe("leave", "Zed");
        expect(rig, problems, "an offline argument accepts a player who joined before", "dprobe cmd Alice seen zed",
                M + "SEEN Alice saw Zed");
        expect(rig, problems, "and an online one", "dprobe cmd Alice seen bob", M + "SEEN Alice saw Bob");
        expect(rig, problems, "a name nobody has used is a usage refusal", "dprobe cmd Alice seen Nobody",
                OUT + "seen command=commands:seen outcome=usage");
        expect(rig, problems, "without offline the same name is a usage refusal", "dprobe cmd Alice pay Zed 5",
                OUT + "pay command=commands:pay outcome=usage");
        completions(rig, problems, "an offline argument completes the online players and those who joined before",
                "seen <>", "[Alice, Bob, Zed]");

        // selector: refused to a sender who may not use selectors, then (as an operator) one run per matched player
        // the server refuses the selector while parsing (Paper's own check of the vanilla selector permission); the
        // end-of-run count of runs shows nothing ran for it
        rig.command("dprobe cmd Alice hug @a", "MATERIONE-DPROBE cmd ");
        expect(rig, problems, "a plain name still works", "dprobe cmd Alice hug bob", M + "HUG Alice hugs Bob");
        rig.command("op Alice", "Made Alice a server operator");
        expect(rig, problems, "@s is the sender", "dprobe cmd Alice hug @s", M + "HUG Alice hugs Alice");
        expect(rig, problems, "@a runs the flow once for each player it matches", "dprobe cmd Alice hug @a",
                M + "HUG Alice hugs Bob");
        expect(rig, problems, "a selector with a filter matches only who it names", "dprobe cmd Alice hug @a[name=Bob]",
                M + "HUG Alice hugs Bob");
        expect(rig, problems, "a selector that matches nobody runs nothing",
                "dprobe cmd Alice hug @a[name=Nobody]", "MATERIONE-OPEN-COMMAND alias=hug outcome=nomatch");
        rig.command("deop Alice", "Made Alice no longer a server operator");

        // rule options on a command: one use of the cooldown per typed command
        expect(rig, problems, "a cooldown lets the first run through", "dprobe cmd Alice daily", M + "DAILY Alice");
        expect(rig, problems, "and holds the second back", "dprobe cmd Alice daily",
                OUT + "daily command=commands:daily outcome=cooldown");
        expect(rig, problems, "on_cooldown is the message: first run", "dprobe cmd Alice waits", M + "WAITS Alice ran");
        expect(rig, problems, "on_cooldown is the message: second run", "dprobe cmd Alice waits",
                M + "WAITS Alice cooling true");

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

    /**
     * What the server sends Alice as her command list (Paper's {@code PlayerCommandSendEvent}, fired with the tree already
     * filtered by each node's {@code requires}): which of {@code names} she is shown. This is what her client completes.
     */
    private static void commandList(CrashRig rig, List<String> problems, String what, String expected, String... names)
            throws Exception {
        try {
            String line = rig.command("dprobe cmdlist Alice " + String.join(" ", names), "MATERIONE-DPROBE cmdlist ");
            if (!line.contains("visible=" + expected)) {
                problems.add(what + ": expected visible=" + expected + " but the probe said: " + line);
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
