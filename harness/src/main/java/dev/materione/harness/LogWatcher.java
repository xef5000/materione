package dev.materione.harness;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
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
    private Process process;

    public record Cursor(String content, boolean partialLine) {}

    public LogWatcher(Path file) {
        this.file = file;
    }

    public void watch(Process child) {
        this.process = child;
    }

    /**
     * Waits until the file contains {@code marker} (as a substring of any
     * line), up to the deadline.
     *
     * @return the matched line, or null on timeout
     */
    public String awaitLine(String marker, Duration timeout) throws IOException, InterruptedException {
        return awaitLine(marker, timeout, new Cursor("", false));
    }

    /**
     * Waits for {@code marker} in content written after {@code cursor} —
     * the cursor from {@link #mark()}. Without the cursor, a repeat command
     * (a second reload producing the same marker) would instantly match the
     * stale first occurrence.
     */
    public String awaitLine(String marker, Duration timeout, Cursor cursor)
            throws IOException, InterruptedException {
        long start = System.nanoTime();
        long deadline = start + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            String content = readAll();
            if (content != null) {
                boolean appended = content.startsWith(cursor.content());
                String fresh = appended
                        ? content.substring(cursor.content().length()) : content;
                if (appended && cursor.partialLine()) {
                    fresh = fresh.contains("\n") ? fresh.substring(fresh.indexOf('\n') + 1) : "";
                }
                for (String line : fresh.substring(0, fresh.lastIndexOf('\n') + 1).split("\n", -1)) {
                    if (line.contains(marker)) {
                        HarnessResult.phase(file.getParent().getParent(), "wait", start, marker);
                        return line;
                    }
                }
            }
            if (process != null && !process.isAlive()) {
                throw new IOException("server exited while awaiting '" + marker + "'"
                        + " (pid=" + process.pid() + ", exit=" + process.exitValue()
                        + ", log=" + file + ")");
            }
            Thread.sleep(200);
        }
        HarnessResult.phase(file.getParent().getParent(), "timeout", start, marker);
        return null;
    }

    /**
     * The current end of the log content: pass to
     * {@link #awaitLine(String, Duration, Cursor)} so only lines written after
     * this point can match.
     */
    public Cursor mark() {
        Cursor snapshot = snapshot();
        return snapshot == null ? new Cursor("", false) : snapshot;
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
        Cursor snapshot = snapshot();
        return snapshot == null ? null : snapshot.content();
    }

    private Cursor snapshot() {
        try {
            byte[] bytes = Files.readAllBytes(file);
            int length = bytes.length;
            // A live append can end midway through a UTF-8 character or URL.
            while (length > 0 && bytes[length - 1] != '\n') {
                length--;
            }
            String content = StandardCharsets.UTF_8.newDecoder()
                    .decode(java.nio.ByteBuffer.wrap(bytes, 0, length)).toString();
            return new Cursor(content, length != bytes.length);
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException("could not read harness log " + file, e);
        }
    }
}
