package dev.materione.plugin.gui.event;

import dev.materione.api.logic.Facets;
import dev.materione.behavior.logic.MapLogicContext;
import dev.materione.plugin.gui.Viewer;
import dev.materione.plugin.gui.session.GuiSession;
import dev.materione.plugin.runtime.behavior.PlaceholderResolver;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Builds the context a GUI rule runs against (logic.md §2.6, §6.2): the variable tree the trigger
 * descriptions declare ({@code player.*}, {@code server.*}, {@code gui.*}, {@code click.*},
 * {@code layer.id}, mutable {@code vars}) and the facets an action reaches for (the player, the
 * session). One place builds it, so every GUI trigger offers the same {@code gui.*} and the schema in
 * {@link dev.materione.plugin.gui.trigger.GuiTriggers} stays true.
 *
 * <p>{@code gui.args} and {@code gui.session} are the session's own maps, not copies: a rule that
 * writes a session variable sees it on the next read, and a restored session shows what it had.
 */
public final class GuiContexts {

    private GuiContexts() {}

    /**
     * @param extra further top-level variables of the trigger ({@code click}, {@code layer}); may be empty
     */
    public static MapLogicContext build(Viewer viewer, GuiSession session, Map<String, Object> extra) {
        var root = new LinkedHashMap<String, Object>();
        root.put("player", playerVars(viewer));
        root.put("server", serverVars());
        var gui = new LinkedHashMap<String, Object>();
        gui.put("id", session.gui().toString());
        gui.put("args", session.args());
        gui.put("session", session.vars());
        gui.put("page", session.pages());
        root.put("gui", gui);
        root.put("vars", new LinkedHashMap<String, Object>());
        root.putAll(extra);

        MapLogicContext context = new MapLogicContext(root).with(GuiSession.FACET, session);
        Player player = viewer.player();
        if (player != null) {
            context.with(Facets.PLAYER, player)
                    .withPlaceholders(text -> PlaceholderResolver.apply(player, text));
        }
        return context;
    }

    /** The {@code click.*} variables of one click: its kind, the clicked slot and the key of a number-key click. */
    public static Map<String, Object> click(String type, int slot, int key) {
        var click = new LinkedHashMap<String, Object>();
        click.put("type", type);
        click.put("slot", slot);
        click.put("key", key);
        return Map.of("click", click);
    }

    private static Map<String, Object> playerVars(Viewer viewer) {
        var vars = new LinkedHashMap<String, Object>();
        vars.put("name", viewer.name());
        vars.put("uuid", viewer.id().toString());
        Player player = viewer.player();
        if (player != null) {
            vars.put("gamemode", player.getGameMode().name().toLowerCase(Locale.ROOT));
            vars.put("sneaking", player.isSneaking());
            vars.put("op", player.isOp());
            vars.put("health", player.getHealth());
            vars.put("level", player.getLevel());
        }
        return vars;
    }

    private static Map<String, Object> serverVars() {
        var vars = new LinkedHashMap<String, Object>();
        try {
            vars.put("online", Bukkit.getOnlinePlayers().size());
            vars.put("max", Bukkit.getMaxPlayers());
        } catch (RuntimeException noServer) {
            // a server-less test: the variables read as absent
        }
        return vars;
    }
}
