package dev.materione.platform.items;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.inventory.ItemStack;

/**
 * Length-framed byte encoding for a list of stacks — the container
 * serialization used by soulbound stashes (player PDC), backpacks (item
 * PDC) and furniture storage (root PDC). Frames are
 * {@code int length + serializeAsBytes} with an empty-list short circuit;
 * corrupt tails are dropped rather than thrown on (fail-soft, D6).
 */
public final class ItemListCodec {

    private ItemListCodec() {}

    /** Encodes stacks; empty input encodes to a zero-length array. */
    public static byte[] encode(List<ItemStack> stacks) {
        if (stacks.isEmpty()) {
            return new byte[0];
        }
        var bytes = new ByteArrayOutputStream();
        try {
            var out = new DataOutputStream(bytes);
            out.writeInt(stacks.size());
            for (ItemStack stack : stacks) {
                if (stack == null || stack.getType().isAir()) {
                    out.writeInt(0);
                    continue;
                }
                byte[] frame = stack.serializeAsBytes();
                out.writeInt(frame.length);
                out.write(frame);
            }
        } catch (IOException e) {
            throw new IllegalStateException("in-memory encode failed", e); // ByteArrayOutputStream never throws
        }
        return bytes.toByteArray();
    }

    /** Decodes stacks; null/empty input decodes to an empty list. */
    public static List<ItemStack> decode(byte[] encoded) {
        var out = new ArrayList<ItemStack>();
        if (encoded == null || encoded.length == 0) {
            return out;
        }
        try {
            var in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(encoded));
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                int length = in.readInt();
                if (length <= 0) {
                    out.add(null); // preserved slot (air) — keep positions stable
                    continue;
                }
                byte[] frame = new byte[length];
                in.readFully(frame);
                out.add(ItemStack.deserializeBytes(frame));
            }
        } catch (IOException | IllegalArgumentException e) {
            // torn or foreign payload: keep what decoded so far (fail soft)
        }
        return out;
    }
}
