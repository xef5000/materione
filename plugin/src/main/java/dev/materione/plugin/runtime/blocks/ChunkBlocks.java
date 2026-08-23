package dev.materione.plugin.runtime.blocks;

import dev.materione.api.NamespacedId;
import java.util.Optional;
import org.bukkit.NamespacedKey;
import org.bukkit.Chunk;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * The chunk-PDC placement store (runtime-systems §4.2): one STRING entry per
 * placed custom block under a local key {@code x{x&15}y{y}z{z&15}}. The PDC
 * is the <strong>truth</strong> for what occupies a position; the decoded
 * blockstate slot is only an accelerator — this ordering protects worlds from
 * allocation drift and vanilla state collisions. Writes batch naturally:
 * every caller mutates all positions of one event before returning, so a
 * chunk is dirtied once per operation, not once per field.
 */
public final class ChunkBlocks {

    /** PDC key prefix namespace; local keys follow. */
    public static final String NAMESPACE = "materione";

    private static final NamespacedKey KEY = NamespacedKey.fromString(NAMESPACE + ":block");

    private ChunkBlocks() {}

    /** The recorded custom block at {@code (x,y,z)}, if any. */
    public static Optional<NamespacedId> idAt(Chunk chunk, int x, int y, int z) {
        PersistentDataContainer pdc = chunk.getPersistentDataContainer();
        String raw = pdc.get(key(x, y, z), PersistentDataType.STRING);
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(NamespacedId.parse(raw));
        } catch (IllegalArgumentException e) {
            return Optional.empty(); // corrupt tag: surfaced by callers as an orphan
        }
    }

    /** Records or clears ({@code id == null}) the custom block at {@code (x,y,z)}. */
    public static void setIdAt(Chunk chunk, int x, int y, int z, NamespacedId id) {
        PersistentDataContainer pdc = chunk.getPersistentDataContainer();
        NamespacedKey key = key(x, y, z);
        if (id == null) {
            pdc.remove(key);
        } else {
            pdc.set(key, PersistentDataType.STRING, id.toString());
        }
    }

    private static NamespacedKey key(int x, int y, int z) {
        return new NamespacedKey(NAMESPACE, "x" + (x & 15) + "y" + y + "z" + (z & 15));
    }
}
