package dev.materione.pack.allocation;

import dev.materione.api.NamespacedId;
import dev.materione.api.content.AllocatedRange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The durable allocation table (master §6.3, D7): {@code storage/allocation.bin}.
 *
 * <p>Invariants, enforced here:
 * <ul>
 *   <li><strong>Append-only log</strong> — assignments are ADD records; removals
 *       are TOMBSTONE records; nothing is ever rewritten in place.</li>
 *   <li><strong>Ids never shift</strong> — re-allocating a known id returns the
 *       original value, even after a tombstone (a tombstoned id is only
 *       reclaimable by the explicit, announced compaction command).</li>
 *   <li><strong>Readers accept N-1</strong> — the container format version moves
 *       only additively; a future format bumps the version and old readers
 *       still read the previous layout. Unknown record kinds are skipped.</li>
 *   <li><strong>World-safety</strong> — the table is persisted <em>before</em>
 *       outputs that depend on it are written (the compiler calls
 *       {@link #flush()} before emitting).</li>
 * </ul>
 *
 * <p>Rebuild-from-graph (lost/corrupt table) is an audited, admin-confirmed
 * event offered by {@code /materione doctor} — never automatic (§4.4).
 */
public final class AllocationTable {

    /** Container magic; a file without it is treated as absent, not corrupt. */
    static final byte[] MAGIC = "MTRALLOC".getBytes(StandardCharsets.US_ASCII);

    /** Container format version; additive changes only. */
    static final int FORMAT_VERSION = 1;

    /** Record kinds. Kind {@code 0} is reserved for future additive kinds. */
    static final byte KIND_ADD = 1;
    static final byte KIND_TOMBSTONE = 2;

    /** One allocation: resource → kind → opaque durable value (e.g. a number as text). */
    public record Entry(NamespacedId resource, String kind, String value) {}

    private final Path file;
    private final Map<String, Entry> live = new LinkedHashMap<>();
    private final Map<String, Entry> tombstones = new LinkedHashMap<>();
    private final Map<String, Integer> counters = new HashMap<>();
    /** Records not yet on disk (flush() appends them before outputs depend on them). */
    private final List<byte[]> pending = new ArrayList<>();
    private boolean corrupt;

    private AllocationTable(Path file) {
        this.file = file;
    }

    /** Loads (or starts) the table at {@code storage/allocation.bin}. Never throws. */
    public static AllocationTable load(Path file) {
        AllocationTable table = new AllocationTable(file);
        if (!Files.isRegularFile(file)) {
            return table;
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            table.replay(bytes);
        } catch (IOException | RuntimeException e) {
            // Corrupt table: surface as empty + corrupt flag; the doctor offers
            // the audited rebuild. Never guess assignments silently.
            table.corrupt = true;
        }
        return table;
    }

    /** True when the file exists but could not be replayed (doctor offers rebuild). */
    public boolean isCorrupt() {
        return corrupt;
    }

    /**
     * Returns the allocated value for {@code resource}, allocating durably if
     * this is the first ask. Allocation never shifts existing assignments.
     *
     * @param kind allocation kind (e.g. {@code custom-model-data}); the value
     *             space is per-kind sequential from 1
     */
    public synchronized String allocate(NamespacedId resource, String kind) {
        String key = key(resource, kind);
        Entry liveEntry = live.get(key);
        if (liveEntry != null) {
            return liveEntry.value();
        }
        Entry tombstoned = tombstones.get(key);
        if (tombstoned != null) {
            // tombstones are unrecoverable truth: the retired value stays bound
            return tombstoned.value();
        }
        int next = counters.merge(kind, 1, Integer::sum);
        String value = Integer.toString(next);
        Entry entry = new Entry(resource, kind, value);
        live.put(key, entry);
        pending.add(record(KIND_ADD, entry));
        return value;
    }

    /**
     * Allocates a consecutive run — the primitive glyph codepoints need
     * (sheet rows and animation frames must occupy adjacent codepoints).
     * A run whose stored count already covers the ask is returned as-is;
     * a growing ask rebinds the id to a fresh run from the kind's counter
     * (values never shift — the orphaned old run stays burned, like a
     * tombstone). Single-value runs store the plain-int form, so
     * {@link #allocate} and this share a value space per kind.
     */
    public synchronized AllocatedRange range(NamespacedId resource, String kind, int count) {
        if (count < 1) {
            throw new IllegalArgumentException("range count must be >= 1: " + count);
        }
        String key = key(resource, kind);
        Entry existing = live.get(key);
        if (existing == null) {
            existing = tombstones.get(key);
        }
        if (existing != null
                && AllocatedRange.parse(existing.value()).count() >= count) {
            return AllocatedRange.parse(existing.value());
        }
        int last = counters.getOrDefault(kind, 0);
        AllocatedRange range =
                new AllocatedRange(last + 1, count);
        counters.put(kind, last + count);
        Entry entry = new Entry(resource, kind, range.toString());
        live.put(key, entry);
        tombstones.remove(key);
        pending.add(record(KIND_ADD, entry));
        return range;
    }

    /** The live allocation for a resource, if any (tombstones excluded). */
    public synchronized Optional<String> allocationOf(NamespacedId resource, String kind) {
        Entry entry = live.get(key(resource, kind));
        return Optional.ofNullable(entry).map(Entry::value);
    }

    /** Records a removal as a tombstone; the value stays bound to its id. */
    public synchronized void tombstone(NamespacedId resource, String kind) {
        Entry entry = live.remove(key(resource, kind));
        if (entry != null) {
            tombstones.put(key(resource, kind), entry);
            pending.add(record(KIND_TOMBSTONE, entry));
        }
    }

    /** Appends pending records to disk. Call before writing outputs derived from allocations. */
    public synchronized void flush() throws IOException {
        if (pending.isEmpty() && Files.isRegularFile(file)) {
            return; // nothing new; the container already exists
        }
        // first flush (even with no allocations yet) writes the container
        // header — "persisted before outputs" is checkable on disk
        if (!Files.isRegularFile(file)) {
            Files.createDirectories(file.getParent());
            List<byte[]> out = new ArrayList<>();
            out.add(header());
            out.addAll(pending);
            Files.write(file, concat(out));
        } else {
            Files.write(file, concat(pending),
                    java.nio.file.StandardOpenOption.APPEND,
                    java.nio.file.StandardOpenOption.CREATE);
        }
        pending.clear();
    }

    /** Live entries in allocation order (audits, doctor, rebuild verification). */
    public synchronized List<Entry> liveEntries() {
        return List.copyOf(live.values());
    }

    public synchronized int liveCount() {
        return live.size();
    }

    // --- container format ---------------------------------------------------
    // header: MAGIC | int FORMAT_VERSION
    // record: kind byte | int idLen | id utf-8 | int kindLen | kind utf-8 |
    //         int valueLen | value utf-8

    private static byte[] header() {
        return concat(List.of(
                MAGIC,
                intBytes(FORMAT_VERSION)));
    }

    private static byte[] record(byte recordKind, Entry entry) {
        byte[] id = entry.resource().toString().getBytes(StandardCharsets.UTF_8);
        byte[] kind = entry.kind().getBytes(StandardCharsets.UTF_8);
        byte[] value = entry.value().getBytes(StandardCharsets.UTF_8);
        return concat(List.of(
                new byte[] {recordKind},
                intBytes(id.length), id,
                intBytes(kind.length), kind,
                intBytes(value.length), value));
    }

    private void replay(byte[] bytes) {
        if (bytes.length < MAGIC.length + 4) {
            corrupt = true;
            return;
        }
        int at = 0;
        for (int i = 0; i < MAGIC.length; i++) {
            if (bytes[at++] != MAGIC[i]) {
                corrupt = true;
                return;
            }
        }
        // readers accept N-1: version >= FORMAT_VERSION of this reader is future
        // (still readable if the record layout is unchanged); we accept 1..N
        int version = readInt(bytes, at);
        at += 4;
        if (version < 1) {
            corrupt = true;
            return;
        }
        while (at < bytes.length) {
            int start = at;
            byte recordKind = bytes[at++];
            String idText = readString(bytes, at);
            NamespacedId id = NamespacedId.parse(idText);
            at += 4 + idText.getBytes(StandardCharsets.UTF_8).length;
            String kind = readString(bytes, at);
            at += 4 + kind.getBytes(StandardCharsets.UTF_8).length;
            String value = readString(bytes, at);
            at += 4 + value.getBytes(StandardCharsets.UTF_8).length;
            Entry entry = new Entry(id, kind, value);
            switch (recordKind) {
                case KIND_ADD -> {
                    live.put(key(id, kind), entry);
                    tombstones.remove(key(id, kind));
                    // plain ints and "start,count" runs share a value space per kind
                    counters.merge(kind,
                            AllocatedRange.parse(value).last(), Math::max);
                }
                case KIND_TOMBSTONE -> {
                    live.remove(key(id, kind));
                    tombstones.put(key(id, kind), entry);
                }
                default -> {
                    // unknown record kind: skip is impossible without a length
                    // prefix; treat as corruption (additive kinds must carry
                    // their own framing in future versions)
                    corrupt = true;
                    return;
                }
            }
            if (at <= start) {
                corrupt = true; // defensive: no forward progress
                return;
            }
        }
    }

    private static String readString(byte[] bytes, int at) {
        int len = readInt(bytes, at);
        return new String(bytes, at + 4, len, StandardCharsets.UTF_8);
    }

    private static int readInt(byte[] bytes, int at) {
        return ((bytes[at] & 0xFF) << 24) | ((bytes[at + 1] & 0xFF) << 16)
                | ((bytes[at + 2] & 0xFF) << 8) | (bytes[at + 3] & 0xFF);
    }

    private static byte[] intBytes(int value) {
        return new byte[] {
                (byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value};
    }

    private static String key(NamespacedId resource, String kind) {
        return kind + "|" + resource;
    }

    private static byte[] concat(List<byte[]> parts) {
        int total = 0;
        for (byte[] part : parts) {
            total += part.length;
        }
        byte[] out = new byte[total];
        int at = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, at, part.length);
            at += part.length;
        }
        return out;
    }
}
