package dev.materione.plugin.escrow.io;

import dev.materione.plugin.escrow.model.Account;
import dev.materione.plugin.escrow.model.AccountCodec;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

/**
 * Writes player files the way the server does (gui-input-durability.md §3 F1, F2) for the read-back's tests: gzip NBT
 * with an inventory, the plugin's {@code BukkitValues} compound and the account in it, replaced by the server's own
 * dance (a temporary file, {@code .dat} moved to {@code .dat_old}, the temporary file moved to {@code .dat}).
 */
public final class PlayerDatFiles {

    public static final String KEY = "materione:gui_escrow";

    private PlayerDatFiles() {}

    /** An account of generation {@code gen} with no entries, as the plugin encodes it. */
    public static byte[] account(long gen) {
        return AccountCodec.encode(new Account(gen, java.util.List.of()));
    }

    /**
     * Uncompressed player NBT: some inventory ({@code padding} items, to make the file big enough to be read slowly),
     * a few foreign values and {@code account} under {@link #KEY} (omitted when null; the whole compound omitted when
     * {@code bukkitValues} is false).
     */
    public static byte[] nbt(byte[] account, int padding, boolean bukkitValues) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(10);
            out.writeUTF("");
            out.writeByte(3);
            out.writeUTF("DataVersion");
            out.writeInt(4189);
            out.writeByte(9);
            out.writeUTF("Inventory");
            out.writeByte(10);
            out.writeInt(padding);
            for (int i = 0; i < padding; i++) {
                out.writeByte(1);
                out.writeUTF("Slot");
                out.writeByte(i);
                out.writeByte(8);
                out.writeUTF("id");
                out.writeUTF("minecraft:diamond");
                out.writeByte(3);
                out.writeUTF("count");
                out.writeInt(i + 1);
                out.writeByte(0);
            }
            out.writeByte(12);
            out.writeUTF("UUID");
            out.writeInt(2);
            out.writeLong(1);
            out.writeLong(2);
            if (bukkitValues) {
                out.writeByte(10);
                out.writeUTF("BukkitValues");
                out.writeByte(8);
                out.writeUTF("other:note");
                out.writeUTF("hello");
                out.writeByte(7);
                out.writeUTF("other:blob");
                out.writeInt(3);
                out.write(new byte[] {1, 2, 3});
                if (account != null) {
                    out.writeByte(7);
                    out.writeUTF(KEY);
                    out.writeInt(account.length);
                    out.write(account);
                }
                out.writeByte(0);
            }
            out.writeByte(0);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }

    public static byte[] gzip(byte[] raw) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (OutputStream out = new GZIPOutputStream(bytes)) {
                out.write(raw);
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }

    /** The server's save: temp file, {@code .dat} to {@code .dat_old}, temp to {@code .dat}. */
    public static void save(Path dir, UUID player, byte[] account, int padding) throws IOException {
        Files.createDirectories(dir);
        Path temp = Files.createTempFile(dir, player.toString(), ".dat");
        Files.write(temp, gzip(nbt(account, padding, true)));
        replace(dir, player, temp);
    }

    /** The two renames of {@code Util.safeReplaceFile} (F2). */
    public static void replace(Path dir, UUID player, Path temp) throws IOException {
        Path dat = dir.resolve(player + ".dat");
        Path old = dir.resolve(player + ".dat_old");
        if (Files.exists(dat)) {
            Files.move(dat, old, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(temp, dat, StandardCopyOption.REPLACE_EXISTING);
    }
}
