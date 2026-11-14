package dev.materione.core.authoring;

import dev.materione.api.authoring.TransactionResult.Kind;
import dev.materione.core.document.FlatJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * What an applied authoring transaction leaves behind so that it can be reverted (codemode.md section 10, #344):
 * one file per transaction under {@code storage/transactions/}, holding the transaction's id, who applied it,
 * and, for each document it wrote, the revision the document had before and after, the hash it was left with,
 * and the text it had before (the <em>before-image</em>).
 *
 * <p>This is history, never content (D1): nothing reads it to learn what a document says now, and a revert
 * writes the files through the same transaction path as any other change. A record is only a recipe for the
 * inverse, and is useless the moment a document moves on, which is exactly when a revert refuses.
 *
 * <p><strong>Retention.</strong> Storage is bounded three ways: a transaction whose before-images exceed
 * {@link #MAX_RECORD_BYTES} is not recorded at all, only the newest {@link #MAX_RECORDS} records are kept, and
 * the kept records total at most {@link #MAX_TOTAL_BYTES}; the oldest are deleted first. A transaction that is not
 * recorded, or has been pruned, cannot be reverted, and says so (the apply's result carries
 * {@code revertible=false} and why; a revert of a pruned id is {@code MTR-0104}). Nothing here ever fails the
 * transaction it describes (D6).
 *
 * <p>The format is one JSON object per line (a header, then one line per document), hand-written like the revision
 * journal because {@code core} stays embeddable without a JSON library.
 */
final class TransactionJournal {

    /** The most records kept; the oldest go first. */
    static final int MAX_RECORDS = 100;

    /** The most bytes all kept records take together. */
    static final long MAX_TOTAL_BYTES = 32L * 1024 * 1024;

    /** The most bytes one record may take; a larger transaction is applied but is not revertible. */
    static final long MAX_RECORD_BYTES = 4L * 1024 * 1024;

    private static final Pattern ID = Pattern.compile("tx-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");

    /**
     * One document a transaction wrote. {@code preRevision} is its stored revision before (null for a document the
     * transaction created, or restored without a stored one); {@code postRevision} and {@code postSha256} are what
     * the transaction left (the hash is null for a delete); {@code before} is the text before (null for a created
     * or moved-to document, which a revert deletes); {@code movedFrom} names a move's source.
     */
    record Doc(
            String path, Kind kind, Integer preRevision, int postRevision, String postSha256, String movedFrom,
            String before) {}

    /** One transaction's record. {@code revertOf} names the transaction a revert undid (null otherwise). */
    record Entry(String id, String pack, String actor, String origin, long timestampMs, String revertOf,
            List<Doc> documents) {}

    /** The outcome of {@link #store}: whether the record is kept, and when it is not, why. */
    record Stored(boolean kept, String note) {}

    /** The outcome of {@link #load}: the record, or the reason there is none. */
    record Loaded(Entry entry, String problem) {}

    private final Path directory;
    private final int maxRecords;
    private final long maxTotalBytes;
    private final long maxRecordBytes;
    private final Object lock = new Object();
    private long lastTimestamp;
    private long sequence;

    TransactionJournal(Path directory) {
        this(directory, MAX_RECORDS, MAX_TOTAL_BYTES, MAX_RECORD_BYTES);
    }

    /** The caps are parameters so that a test can reach them without writing megabytes. */
    TransactionJournal(Path directory, int maxRecords, long maxTotalBytes, long maxRecordBytes) {
        this.directory = directory;
        this.maxRecords = maxRecords;
        this.maxTotalBytes = maxTotalBytes;
        this.maxRecordBytes = maxRecordBytes;
    }

    static boolean isTransactionId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    /** The retention rule in a sentence, for the messages that name it. */
    String retentionRule() {
        return "the last " + maxRecords + " transactions are kept, at most " + (maxTotalBytes / (1024 * 1024))
                + " MiB in all and " + (maxRecordBytes / (1024 * 1024)) + " MiB for one";
    }

    // ------------------------------------------------------------------
    // store

    /** Writes the record (atomically) and prunes. Never throws: a failure is a note, and the transaction stands. */
    Stored store(Entry entry) {
        byte[] bytes;
        try {
            bytes = encode(entry).getBytes(StandardCharsets.UTF_8);
        } catch (RuntimeException failure) {
            return new Stored(false, "the transaction's record could not be built: " + failure);
        }
        if (bytes.length > maxRecordBytes) {
            return new Stored(false, "this transaction's before-images take " + bytes.length + " bytes, over the "
                    + maxRecordBytes + "-byte limit for one transaction, so it is applied but cannot be reverted");
        }
        synchronized (lock) {
            try {
                Files.createDirectories(directory);
                long now = Math.max(System.currentTimeMillis(), lastTimestamp);
                if (now != lastTimestamp) {
                    sequence = 0;
                }
                lastTimestamp = now;
                String name = String.format("%013d-%06d-%s.tx", now, sequence++, entry.id());
                Path target = directory.resolve(name);
                Path temp = directory.resolve(name + ".tmp");
                Files.write(temp, bytes);
                try {
                    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(temp, target);
                }
                prune();
                return new Stored(true, null);
            } catch (IOException | RuntimeException failure) {
                return new Stored(false, "the transaction's record could not be written, so it cannot be reverted: "
                        + failure.getMessage());
            }
        }
    }

    /** Deletes the oldest records until the count and the total size are within the caps. */
    private void prune() throws IOException {
        List<Path> files = records();
        long total = 0;
        for (Path file : files) {
            total += Files.size(file);
        }
        int index = 0;
        while (index < files.size() - 1 && (files.size() - index > maxRecords || total > maxTotalBytes)) {
            total -= Files.size(files.get(index));
            Files.deleteIfExists(files.get(index));
            index++;
        }
    }

    /** The record files, oldest first. */
    private List<Path> records() throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> list = Files.list(directory)) {
            return list.filter(path -> path.getFileName().toString().endsWith(".tx"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
    }

    /** How many records are kept (a test and diagnostics seam). */
    int count() {
        synchronized (lock) {
            try {
                return records().size();
            } catch (IOException unreadable) {
                return 0;
            }
        }
    }

    // ------------------------------------------------------------------
    // load

    Loaded load(String id) {
        if (!isTransactionId(id)) {
            return new Loaded(null, "'" + id + "' is not a transaction id");
        }
        synchronized (lock) {
            try {
                for (Path file : records()) {
                    if (file.getFileName().toString().endsWith("-" + id + ".tx")) {
                        return decode(Files.readString(file, StandardCharsets.UTF_8));
                    }
                }
            } catch (IOException | RuntimeException failure) {
                return new Loaded(null, "the record of " + id + " could not be read: " + failure.getMessage());
            }
        }
        return new Loaded(null, "there is no record of " + id + ": it was not applied by this server, or it is past "
                + "the retention limit (" + retentionRule() + ")");
    }

    // ------------------------------------------------------------------
    // the format

    private static String encode(Entry entry) {
        StringBuilder out = new StringBuilder();
        out.append("{\"v\":1,\"id\":").append(quote(entry.id()))
                .append(",\"pack\":").append(quote(entry.pack()))
                .append(",\"actor\":").append(quote(entry.actor()))
                .append(",\"origin\":").append(quote(entry.origin()))
                .append(",\"ts\":").append(entry.timestampMs());
        if (entry.revertOf() != null) {
            out.append(",\"revertOf\":").append(quote(entry.revertOf()));
        }
        out.append(",\"documents\":").append(entry.documents().size()).append("}\n");
        for (Doc doc : entry.documents()) {
            out.append("{\"path\":").append(quote(doc.path()))
                    .append(",\"kind\":").append(quote(doc.kind().name()))
                    .append(",\"pre\":").append(doc.preRevision() == null ? "null" : doc.preRevision().toString())
                    .append(",\"post\":").append(doc.postRevision())
                    .append(",\"sha\":").append(doc.postSha256() == null ? "null" : quote(doc.postSha256()));
            if (doc.movedFrom() != null) {
                out.append(",\"from\":").append(quote(doc.movedFrom()));
            }
            if (doc.before() != null) {
                out.append(",\"before\":").append(quote(doc.before()));
            }
            out.append("}\n");
        }
        return out.toString();
    }

    private static Loaded decode(String text) {
        String[] lines = text.split("\n");
        if (lines.length == 0) {
            return new Loaded(null, "the record is empty");
        }
        Map<String, Object> header = parseObject(lines[0]);
        if (!(header.get("v") instanceof Number version) || version.intValue() != 1) {
            return new Loaded(null, "the record is a newer format than this server reads");
        }
        int expected = ((Number) header.get("documents")).intValue();
        List<Doc> documents = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) {
                continue;
            }
            Map<String, Object> fields = parseObject(lines[i]);
            documents.add(new Doc(
                    (String) fields.get("path"),
                    Kind.valueOf((String) fields.get("kind")),
                    fields.get("pre") == null ? null : ((Number) fields.get("pre")).intValue(),
                    ((Number) fields.get("post")).intValue(),
                    (String) fields.get("sha"),
                    (String) fields.get("from"),
                    (String) fields.get("before")));
        }
        if (documents.size() != expected) {
            return new Loaded(null, "the record is damaged: " + documents.size() + " of " + expected
                    + " documents are present");
        }
        return new Loaded(new Entry(
                (String) header.get("id"), (String) header.get("pack"), (String) header.get("actor"),
                (String) header.get("origin"), ((Number) header.get("ts")).longValue(),
                (String) header.get("revertOf"), documents), null);
    }

    private static Map<String, Object> parseObject(String line) {
        return FlatJson.parseObject(line);
    }

    private static String quote(String raw) {
        return FlatJson.quote(raw);
    }

    /** Whether a record for {@code id} exists on disk (a test seam). */
    Optional<Path> fileOf(String id) {
        synchronized (lock) {
            try {
                return records().stream().filter(file -> file.getFileName().toString().endsWith("-" + id + ".tx"))
                        .findFirst();
            } catch (IOException unreadable) {
                return Optional.empty();
            }
        }
    }
}
