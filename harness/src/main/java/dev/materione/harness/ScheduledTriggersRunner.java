package dev.materione.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** The scheduled-trigger primitive on a real Paper server. */
public final class ScheduledTriggersRunner {

    public HarnessResult run(HarnessOptions options) {
        List<String> problems = new ArrayList<>();
        CrashRig rig = null;
        try {
            rig = CrashRig.createForEscrow(options, "scheduled-triggers", null, Set.of("timers"));
            rig.boot();
            rig.command("materione var set - timers:count 0", "timers:count = 0");
            rig.command("materione var set - timers:enabled true", "timers:enabled = true");
            if (rig.log().awaitLine("MATERIONE-TIMERS-TICK 1", rig.commandTimeout()) == null) {
                problems.add("the one-second server timer did not increment its server variable");
            }
            rig.command("materione var set - timers:enabled false", "timers:enabled = false");
            String value = rig.command("materione var get - timers:count", "timers:count =");
            if (!value.matches("(?s).*timers:count = [1-9][0-9]*.*")) {
                problems.add("the server variable did not hold a positive timer count: " + value);
            }
            rig.stop();
            String log = rig.log().readAll();
            if (log != null && (log.contains("MTR-0128") || log.contains("MTR-0021")
                    || log.contains("MTR-0022") || log.contains("MTR-0023"))) {
                problems.add("a timer or rule diagnostic was logged: " + firstProblem(log));
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

    private static String firstProblem(String log) {
        return log.lines()
                .filter(line -> line.contains("MTR-0128") || line.contains("MTR-0021")
                        || line.contains("MTR-0022") || line.contains("MTR-0023"))
                .findFirst().orElse("");
    }
}
