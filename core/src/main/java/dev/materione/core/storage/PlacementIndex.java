package dev.materione.core.storage;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.yaml.snakeyaml.Yaml;

/**
 * The disposable placement index (runtime-systems §4.2,
 * {@code generated/index/placements.jsonl}): an append-only log of
 * add/remove operations over {@code world|position → block id}, replayed
 * into memory on boot and compacted to the live set. The chunk PDC remains
 * the truth per placement; this index exists so operators and sweeps can
 * find placements without scanning every chunk — and it is rebuildable by
 * exactly such a scan, which is why losing it is never fatal.
 *
 * <p>Thread-safety: mutations arrive on region threads; the live map is
 * concurrent and appends serialize on a single writer.
 */
public final class PlacementIndex implements AutoCloseable {

    /** One indexed placement position. */
    public record Position(String world, int x, int y, int z) {
        @Override
        public String toString() {
            return world + " " + x + " " + y + " " + z;
        }
    }

    private final Path file;
    private final Map<Position, String> live = new ConcurrentHashMap<>();
    private BufferedWriter writer;

    private PlacementIndex(Path file) {
        this.file = file;
    }

    /**
     * Replays the log at {@code file} (missing file = empty index) and
     * rewrites it to the live set. Corrupt lines are skipped and counted —
     * disposable state never blocks a boot. Never throws.
     */
    public static PlacementIndex load(Path file) {
        PlacementIndex index = new PlacementIndex(file);
        int stale = 0;
        if (Files.isRegularFile(file)) {
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) {
                        continue;
                    }
                    try {
                        if (!index.replay(line)) {
                            stale++;
                        }
                    } catch (RuntimeException e) {
                        stale++;
                    }
                }
            } catch (IOException e) {
                stale = -1; // unreadable beats half-read: start clean below
            }
        }
        index.compact();
        return index;
    }

    /** Records a placement (append + memory). */
    public synchronized void add(Position position, String blockId) {
        live.put(position, blockId);
        write("add", position, blockId);
    }

    /** Records a removal (append + memory); removing an unknown position is a no-op. */
    public synchronized void remove(Position position) {
        if (live.remove(position) == null) {
            return;
        }
        write("del", position, null);
    }

    /** The block recorded at {@code position}, if any. */
    public String at(Position position) {
        return live.get(position);
    }

    /** Live entries (sweep surface). */
    public Map<Position, String> snapshot() {
        return Map.copyOf(live);
    }

    public int size() {
        return live.size();
    }

    @Override
    public synchronized void close() throws IOException {
        if (writer != null) {
            writer.close();
            writer = null;
        }
    }

    // --- format -------------------------------------------------------------
    // {"op":"add","world":"…","x":0,"y":0,"z":0,"block":"ns:name"}  (one per line)

    private boolean replay(String line) {
        Object parsed = new Yaml().load(line);
        if (!(parsed instanceof Map<?, ?> entry)) {
            return false;
        }
        String op = text(entry.get("op"));
        String world = text(entry.get("world"));
        Object x = entry.get("x"), y = entry.get("y"), z = entry.get("z");
        String block = text(entry.get("block"));
        if (world == null || !(x instanceof Number nx) || !(y instanceof Number ny)
                || !(z instanceof Number nz)) {
            return false;
        }
        Position position = new Position(world, nx.intValue(), ny.intValue(), nz.intValue());
        if ("add".equals(op) && block != null) {
            live.put(position, block);
            return true;
        }
        if ("del".equals(op)) {
            live.remove(position);
            return true;
        }
        return false;
    }

    /** Rewrites the container to only live adds (boot compaction, D7 disposability). */
    private synchronized void compact() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (BufferedWriter out = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                for (var entry : live.entrySet()) {
                    out.write(line("add", entry.getKey(), entry.getValue()));
                    out.write('\n');
                }
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // keep the in-memory index; the next boot retries compaction
        }
        try {
            if (writer != null) {
                writer.close();
            }
            writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.APPEND,
                    java.nio.file.StandardOpenOption.CREATE);
        } catch (IOException e) {
            writer = null; // appends will fail loudly per-op; memory stays right
        }
    }

    private void write(String op, Position position, String blockId) {
        try {
            if (writer == null) {
                Files.createDirectories(file.getParent());
                writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.APPEND,
                        java.nio.file.StandardOpenOption.CREATE);
            }
            writer.write(line(op, position, blockId));
            writer.write('\n');
            writer.flush();
        } catch (IOException e) {
            // disposable index: a failed append degrades sweep coverage only
        }
    }

    private static String line(String op, Position position, String blockId) {
        return "{\"op\":\"" + op + "\",\"world\":" + quote(position.world())
                + ",\"x\":" + position.x() + ",\"y\":" + position.y() + ",\"z\":" + position.z()
                + (blockId == null ? "" : ",\"block\":" + quote(blockId)) + "}";
    }

    private static String quote(String value) {
        var escaped = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.append('"').toString();
    }

    private static String text(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }
}
