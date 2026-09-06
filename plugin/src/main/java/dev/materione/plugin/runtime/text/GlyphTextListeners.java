package dev.materione.plugin.runtime.text;

import dev.materione.core.runtime.RuntimeGlyphs;
import dev.materione.platform.text.GlyphText;
import io.papermc.paper.event.player.AsyncChatDecorateEvent;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;

import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.meta.BookMeta;

/**
 * The emoji-mode surfaces (M4, runtime-systems §6): glyph markers resolve in
 * chat (decorate-time, so chat previews show them too), on signs, in book
 * pages and anvil renames. Permission-gated glyphs stay literal for viewers
 * without them — the raw codepoint never leaks to a client that shouldn't
 * see it. Resolution also gates on the acting player holding a loaded
 * resource pack: a codepoint sent to a client without the matching font
 * renders as the missing-glyph box, so players who declined (or have not
 * yet finished loading) see the literal {@code :placeholder:} instead.
 */
public final class GlyphTextListeners implements Listener {

    private final Supplier<RuntimeGlyphs> glyphs;
    private final Predicate<UUID> packReady;

    public GlyphTextListeners(Supplier<RuntimeGlyphs> glyphs, Predicate<UUID> packReady) {
        this.glyphs = glyphs;
        this.packReady = packReady;
    }

    /** The chat path (also drives the client's chat preview). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChatDecorate(AsyncChatDecorateEvent event) {
        RuntimeGlyphs registry = glyphs.get();
        if (registry == null || registry.isEmpty()) {
            return;
        }
        var player = event.player();
        // null player = console-side decoration; the result is never rendered client-side
        if (player != null && !packReady.test(player.getUniqueId())) {
            return;
        }
        Component current = event.result();
        Component resolved = GlyphText.resolveComponent(current, registry,
                permission -> player == null || player.hasPermission(permission));
        if (resolved != current) {
            event.result(resolved);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        RuntimeGlyphs registry = glyphs.get();
        if (registry == null || registry.isEmpty()) {
            return;
        }
        var player = event.getPlayer();
        if (!packReady.test(player.getUniqueId())) {
            return;
        }
        for (int line = 0; line < event.lines().size(); line++) {
            Component resolved = GlyphText.resolveComponent(event.line(line), registry,
                    permission -> player.hasPermission(permission));
            if (resolved != event.line(line)) {
                event.line(line, resolved);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBookEdit(PlayerEditBookEvent event) {
        RuntimeGlyphs registry = glyphs.get();
        if (registry == null || registry.isEmpty()) {
            return;
        }
        var player = event.getPlayer();
        if (!packReady.test(player.getUniqueId())) {
            return;
        }
        BookMeta meta = event.getNewBookMeta();
        List<Component> pages = meta.pages();
        if (pages.isEmpty()) {
            return;
        }
        boolean changed = false;
        var resolved = new java.util.ArrayList<Component>(pages.size());
        for (Component page : pages) {
            Component done = GlyphText.resolveComponent(page, registry,
                    permission -> player.hasPermission(permission));
            changed |= done != page;
            resolved.add(done);
        }
        if (changed) {
            meta.pages(resolved);
            event.setNewBookMeta(meta);
        }
    }

    /** An anvil rename keeps glyph tags functional in the result's name. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        RuntimeGlyphs registry = glyphs.get();
        if (registry == null || registry.isEmpty() || event.getResult() == null) {
            return;
        }
        var renamer = event.getView().getPlayer();
        if (renamer == null || !packReady.test(renamer.getUniqueId())) {
            return;
        }
        var stack = event.getResult();
        var meta = stack.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) {
            return;
        }
        Component name = meta.displayName();
        Component resolved = GlyphText.resolveComponent(name, registry,
                permission -> true); // the renamer already paid the anvil's XP cost
        if (resolved != name) {
            meta.displayName(resolved);
            stack.setItemMeta(meta);
            event.setResult(stack);
        }
    }

    /** Permitted emoji placeholders become custom chat completions. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        RuntimeGlyphs registry = glyphs.get();
        if (registry == null || registry.isEmpty()) {
            return;
        }
        var player = event.getPlayer();
        List<String> completions = registry.emojiGlyphs().stream()
                .filter(RuntimeGlyphs.Entry::tabComplete)
                .filter(entry -> entry.permission() == null || player.hasPermission(entry.permission()))
                .flatMap(entry -> entry.placeholders().stream())
                .toList();
        if (!completions.isEmpty()) {
            player.addCustomChatCompletions(completions);
        }
    }
}
