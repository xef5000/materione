package dev.materione.harness;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Money whose amount comes from a command argument, on a real Paper server (logic.md section 42, #464). The
 * {@code economy} fixture pack names the harness's test economy (the durability probe's {@code EconomyStub}, Materione's own
 * currency SPI, balances in memory), and two players move stub coins through command documents: a valid payment moves the
 * balances, a refused amount moves nothing and is reported under {@code MTR-0120}.
 *
 * <p>The players are the fake-connection players of {@link CrashRig}; a command is {@code dprobe cmd} (the path a typed
 * command takes), and a balance is {@code dprobe balance}, read from the probe's own map. The IT asserts the money, not the
 * console lines alone. What a real client sees is the QA catalog's {@code economy} suite.
 *
 * <p>This proves the logic on Paper, where every thread check is true; nothing here ran on Folia.
 */
public final class EconomyRunner {

    private static final String M = "MATERIONE-ECON-";

    public HarnessResult run(HarnessOptions options) {
        List<String> problems = new ArrayList<>();
        CrashRig rig = null;
        try {
            rig = CrashRig.createForEscrow(options, "economy", null, Set.of("economy"));
            rig.boot();
            scenario(rig, problems);
            rig.stop();
            String log = rig.log().readAll();
            if (log == null) {
                problems.add("no server log");
            } else {
                // the probe's economy registered, so the currency bound: a missing stub is the first thing to show
                if (!log.contains("economy stub registered")) {
                    problems.add("the harness economy did not register: " + firstLineWith(log, "economy stub"));
                }
                for (String code : List.of("MTR-0021", "MTR-0057", "MTR-0060", "MTR-0061", "MTR-0062", "MTR-0067",
                        "behavior bridge failed")) {
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
        rig.probe("grant", "Alice 100");
        balance(rig, problems, "Alice", "100.0", "the stub economy starts with the granted coins");
        balance(rig, problems, "Bob", "0.0", "a player with no grant holds nothing");

        // a valid payment with no requirement and no require: the take and the give run on the amount from the argument
        expect(rig, problems, "a raw payment moves the coins to the other player", "dprobe cmd Alice coinpay-raw Bob 30",
                M + "RAW Alice to Bob amount 30");
        balance(rig, problems, "Alice", "70.0", "the raw payer paid 30");
        balance(rig, problems, "Bob", "30.0", "the raw payee received 30");

        // the same payment through the requirement and the require: the take runs inside it
        expect(rig, problems, "a payment moves the coins to the other player", "dprobe cmd Alice coinpay Bob 10",
                M + "PAY Alice to Bob amount 10");
        balance(rig, problems, "Alice", "60.0", "the payer paid 10 more");
        balance(rig, problems, "Bob", "40.0", "the payee received 10 more");

        // the requirement refuses a non-positive amount before any money verb runs
        expect(rig, problems, "a non-positive amount is denied by the requirement", "dprobe cmd Alice coinpay Bob -3",
                M + "DENIED Alice amount -3");
        balance(rig, problems, "Alice", "60.0", "a denied payment moves nothing from the payer");
        balance(rig, problems, "Bob", "40.0", "a denied payment moves nothing to the payee");

        // the balance cannot cover the amount: the take fails, the else runs, and nothing is given
        expect(rig, problems, "an amount the balance cannot cover takes nothing", "dprobe cmd Alice coinpay Bob 500",
                M + "SHORT Alice amount 500");
        balance(rig, problems, "Alice", "60.0", "a short payment takes nothing from the payer");
        balance(rig, problems, "Bob", "40.0", "a short payment gives nothing to the payee");

        // the amount as text, reaching the money code: a negative, a word, an oversized number and zero each move nothing
        for (String amount : List.of("-5", "abc", "1e13", "0")) {
            // MTR-0120 is rate-limited per rule for a second (plugin.md section 5): space the refusals so each one reports
            Thread.sleep(1100);
            int cursor = rig.log().mark();
            expect(rig, problems, "the refused amount " + amount + " reaches the run", "dprobe cmd Alice coinpay-raw Bob " + amount,
                    M + "RAW Alice to Bob amount " + amount);
            if (rig.log().awaitNewLine("MTR-0120", Duration.ofSeconds(5), cursor) == null) {
                problems.add("the refused amount " + amount + " was not reported under MTR-0120");
            }
            balance(rig, problems, "Alice", "60.0", "the refused amount " + amount + " moves nothing from the payer");
            balance(rig, problems, "Bob", "40.0", "the refused amount " + amount + " moves nothing to the payee");
        }
    }

    /** Reads the player's balance from the probe and checks it is {@code expected}. */
    private static void balance(CrashRig rig, List<String> problems, String name, String expected, String what)
            throws Exception {
        try {
            String line = rig.probe("balance", name).strip(); // the log line may end with a carriage return
            if (!line.endsWith(" " + name + " " + expected)) {
                problems.add(what + ": expected " + name + " to hold " + expected + " but the probe said: " + line);
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
