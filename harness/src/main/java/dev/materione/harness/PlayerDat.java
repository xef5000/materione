package dev.materione.harness;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * A read-only view of a player's {@code playerdata/<uuid>.dat}: the gzip-compressed NBT the server writes for a
 * player (gui-input-durability.md section 3, F1). It is the harness's own minimal NBT reader (compounds become
 * {@link Map}, lists {@link List}, numbers {@link Number}, strings {@link String}, arrays {@code long[]}/{@code int[]}/
 * {@code byte[]}), enough to ask the two questions a crash row asks of the disk: what is in the inventory, and what is in
 * the plugin persistent data ({@code BukkitValues}). Product-independent: it knows no Materione key.
 */
public final class PlayerDat {

    private final Map<String, Object> root;

    private PlayerDat(Map<String, Object> root) {
        this.root = root;
    }

    /** Reads the file; an absent or unreadable file is an {@link IOException}, never a silent empty view. */
    public static PlayerDat read(Path file) throws IOException {
        try (InputStream in = new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file)))) {
            DataInputStream data = new DataInputStream(in);
            byte type = data.readByte();
            if (type != 10) {
                throw new IOException("not a compound root: " + type);
            }
            data.readUTF();
            @SuppressWarnings("unchecked")
            Map<String, Object> root = (Map<String, Object>) readPayload(data, (byte) 10);
            return new PlayerDat(root);
        }
    }

    public Map<String, Object> root() {
        return root;
    }

    /** The inventory as {@code slot -> "<id> x<count>"}, e.g. {@code 3 -> minecraft:diamond x5}. */
    public Map<Integer, String> inventory() {
        Map<Integer, String> out = new LinkedHashMap<>();
        Object list = root.get("Inventory");
        if (list instanceof List<?> entries) {
            for (Object entry : entries) {
                @SuppressWarnings("unchecked")
                Map<String, Object> item = (Map<String, Object>) entry;
                int slot = ((Number) item.get("Slot")).intValue();
                Object count = item.containsKey("count") ? item.get("count") : item.get("Count");
                out.put(slot, item.get("id") + " x" + count);
            }
        }
        return out;
    }

    /** The plugin persistent data compound ({@code BukkitValues}); empty when the player has none. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> bukkitValues() {
        Object values = root.get("BukkitValues");
        return values instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static Object readPayload(DataInputStream in, byte type) throws IOException {
        switch (type) {
            case 1:
                return in.readByte();
            case 2:
                return in.readShort();
            case 3:
                return in.readInt();
            case 4:
                return in.readLong();
            case 5:
                return in.readFloat();
            case 6:
                return in.readDouble();
            case 7: {
                byte[] bytes = new byte[in.readInt()];
                in.readFully(bytes);
                return bytes;
            }
            case 8:
                return in.readUTF();
            case 9: {
                byte elementType = in.readByte();
                int size = in.readInt();
                List<Object> list = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    list.add(readPayload(in, elementType));
                }
                return list;
            }
            case 10: {
                Map<String, Object> compound = new LinkedHashMap<>();
                for (byte next = in.readByte(); next != 0; next = in.readByte()) {
                    String name = in.readUTF();
                    compound.put(name, readPayload(in, next));
                }
                return compound;
            }
            case 11: {
                int[] ints = new int[in.readInt()];
                for (int i = 0; i < ints.length; i++) {
                    ints[i] = in.readInt();
                }
                return ints;
            }
            case 12: {
                long[] longs = new long[in.readInt()];
                for (int i = 0; i < longs.length; i++) {
                    longs[i] = in.readLong();
                }
                return longs;
            }
            default:
                throw new IOException("unknown NBT tag type " + type);
        }
    }
}
