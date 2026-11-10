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
        return awaitLine(marker, timeout, 0);
    }

    /**
     * Waits for {@code marker} occurring at or beyond {@code fromIndex} —
     * the cursor from {@link #mark()}. Without the cursor, a repeat command
     * (a second reload producing the same marker) would instantly match the
     * stale first occurrence.
     */
    public String awaitLine(String marker, Duration timeout, int fromIndex)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            String content = readAll();
            if (content != null && content.indexOf(marker, Math.min(fromIndex, content.length())) >= 0) {
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

    /**
     * Like {@link #awaitLine(String, Duration, int)}, but returns the first matching line <em>at or beyond</em>
     * {@code fromIndex}. {@code awaitLine} waits for a fresh occurrence yet returns the first line of the whole log that
     * contains the marker, which is a stale line when the same marker was printed before; a scenario that repeats one
     * command and reads each answer (the input-durability rig) needs this one.
     */
    public String awaitNewLine(String marker, Duration timeout, int fromIndex) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            String content = readAll();
            if (content != null) {
                int from = Math.min(fromIndex, content.length());
                int at = content.indexOf(marker, from);
                if (at >= 0) {
                    int start = content.lastIndexOf('\n', at) + 1;
                    int end = content.indexOf('\n', at);
                    return content.substring(Math.max(start, 0), end < 0 ? content.length() : end);
                }
            }
            Thread.sleep(200);
        }
        return null;
    }

    /**
     * The current end of the log content: pass to
     * {@link #awaitLine(String, Duration, int)} so only lines written after
     * this point can match.
     */
    public int mark() {
        String content = readAll();
        return content == null ? 0 : content.length();
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
