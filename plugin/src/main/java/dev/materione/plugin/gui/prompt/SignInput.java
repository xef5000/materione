package dev.materione.plugin.gui.prompt;

import dev.materione.api.logic.InputMethod;
import dev.materione.api.logic.InputMethodDescription;
import dev.materione.api.registry.SelfDescription;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;

/**
 * {@code materione:sign} (logic.md §6.6): the player writes the answer on a sign editor. The client can only edit a
 * real sign, so the method places one in the empty top block of the player's column for the question and removes it
 * when the prompt ends. The block must be air; a column with no free block refuses the question.
 *
 * <p>The editor's text is the answer, its lines joined by a space. An editor closed with nothing written is no answer.
 */
public final class SignInput implements InputMethod {

    /** The name an author writes. */
    public static final String ID = "sign";

    private final Map<UUID, Editor> editors = new ConcurrentHashMap<>();

    /** One open editor: where its sign is, what was there before, and where the answer goes. */
    private record Editor(Reply reply, Location at, BlockData original) {}

    @Override
    public String id() {
        return ID;
    }

    @Override
    public InputMethodDescription describe() {
        return InputMethodDescription.of(new SelfDescription("Sign",
                "The player writes the answer on a sign editor that opens for them", List.of(), "1.0.0"), true);
    }

    @Override
    public void ask(Player player, String prompt, Reply reply) {
        Block block = freeBlock(player);
        BlockData original = block.getBlockData();
        block.setBlockData(Material.OAK_SIGN.createBlockData(), false);
        BlockState state = block.getState();
        if (!(state instanceof Sign sign)) {
            block.setBlockData(original, false);
            throw new IllegalStateException("the sign could not be placed for the editor");
        }
        editors.put(player.getUniqueId(), new Editor(reply, block.getLocation(), original));
        try {
            player.openSign(sign, Side.FRONT);
        } catch (RuntimeException failure) {
            editors.remove(player.getUniqueId());
            block.setBlockData(original, false);
            throw failure;
        }
        player.sendMessage(Component.text(prompt, NamedTextColor.AQUA));
    }

    @Override
    public void dismiss(Player player) {
        Editor editor = editors.remove(player.getUniqueId());
        if (editor != null) {
            restore(editor);
        }
    }

    /** Whether the player has a sign editor open for a question. Read from the event thread. */
    public boolean awaits(UUID player) {
        return editors.containsKey(player);
    }

    /**
     * The editor of the player's question was closed with these lines. Call on the player's thread.
     *
     * @return whether the sign was the question's editor
     */
    public boolean edited(UUID player, Location where, String text) {
        Editor editor = editors.get(player);
        if (editor == null || !sameBlock(editor.at(), where)) {
            return false;
        }
        editor.reply().answer(text);
        return true;
    }

    private static Block freeBlock(Player player) {
        Location at = player.getLocation();
        Block block = at.getWorld().getBlockAt(at.getBlockX(), at.getWorld().getMaxHeight() - 1, at.getBlockZ());
        if (!block.getType().isAir()) {
            throw new IllegalStateException("there is no free block above the player for the sign editor");
        }
        return block;
    }

    private static void restore(Editor editor) {
        Block block = editor.at().getBlock();
        block.setBlockData(editor.original(), false);
    }

    private static boolean sameBlock(Location a, Location b) {
        return a.getWorld() != null && a.getWorld().equals(b.getWorld())
                && a.getBlockX() == b.getBlockX() && a.getBlockY() == b.getBlockY() && a.getBlockZ() == b.getBlockZ();
    }
}
