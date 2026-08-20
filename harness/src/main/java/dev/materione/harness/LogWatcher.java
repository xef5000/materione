package dev.materione.harness;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Polls a log file for markers (no threads, no watchers — a simple
 * read-and-scan loop with a deadline; log volumes here are small).
 */
public final class LogWatcher {

    private final Path file;

    public LogWatcher(Path file) {
        this.file = file;
    }

    /**
     * Waits until the file contains {@code marker} (as a substring of any
     * line), up to the deadline.
     *
     * @return the matched line, or null on timeout
     */
    public String awaitLine(String marker, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            String content = readAll();
            if (content != null) {
                for (String line : content.split("\n", -1)) {
                    if (line.contains(marker)) {
                        return line;
                    }
                }
            }
            Thread.sleep(200);
        }
        return null;
    }

    /** The last {@code n} non-blank lines of the file (empty if absent). */
    public List<String> tail(int n) {
        String content = readAll();
        if (content == null || content.isBlank()) {
            return List.of();
        }
        List<String> nonBlank = new ArrayList<>();
        for (String line : content.split("\n", -1)) {
            if (!line.isBlank()) {
                nonBlank.add(line.trim());
            }
        }
        return nonBlank.subList(Math.max(0, nonBlank.size() - n), nonBlank.size());
    }

    public String readAll() {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }
}
