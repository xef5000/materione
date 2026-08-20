package dev.materione.harness;

import java.nio.file.Path;
import java.util.List;

/** Outcome of one harness run. */
public record HarnessResult(
        boolean ok,
        List<String> problems,
        int exitCode,
        Path workDir,
        List<String> logTail) {

    public HarnessResult {
        problems = List.copyOf(problems);
        logTail = List.copyOf(logTail);
    }

    /** Human-readable summary for test-failure messages. */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(ok ? "harness run OK" : "harness run FAILED");
        sb.append(" (exit ").append(exitCode).append(", workspace ").append(workDir).append(")\n");
        if (!problems.isEmpty()) {
            sb.append("problems:\n");
            for (String problem : problems) {
                sb.append("  - ").append(problem).append('\n');
            }
        }
        sb.append("last log lines:\n");
        for (String line : logTail) {
            sb.append("  | ").append(line).append('\n');
        }
        return sb.toString();
    }
}
