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
        problems = problems.stream().map(HarnessResult::redact).toList();
        logTail = logTail.stream().map(HarnessResult::redact).toList();
        System.out.println("MTR-HARNESS result=" + (ok ? "PASS" : "FAIL")
                + " exit=" + exitCode + " workspace=" + workDir);
    }

    static void phase(Path workspace, String phase, long start, String detail) {
        System.out.println("MTR-HARNESS phase=" + phase
                + " elapsedMs=" + java.time.Duration.ofNanos(System.nanoTime() - start).toMillis()
                + " workspace=" + workspace + " detail=" + redact(detail));
    }

    static String redact(String text) {
        return text.replaceAll("(https?://[^\\s\"'<>\\\\]*[?&]s=)[A-Za-z0-9_-]+(#[A-Za-z0-9_-]+)?",
                        "$1[REDACTED]")
                .replaceAll("(https?://[^\\s\"'<>\\\\]*?/s/)[A-Za-z0-9_-]+(#[A-Za-z0-9_-]+)?",
                        "$1[REDACTED]")
                .replaceAll("(\\beditor session )[A-Za-z0-9_-]{20,}", "$1[REDACTED]");
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
