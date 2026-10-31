package dev.materione.plugin.gui.event;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.Facets;
import dev.materione.api.logic.variable.VariableFacets;
import dev.materione.behavior.logic.MapLogicContext;
import dev.materione.plugin.gui.Viewer;
import dev.materione.plugin.gui.session.GuiSession;
import dev.materione.plugin.gui.session.GuiSessionVariables;
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
 * session, and the session's variables as {@code vars.session.*}). One place builds it, so every GUI
 * trigger offers the same {@code gui.*} and the schema in
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
        return withPlayer(new MapLogicContext(root)
                .with(GuiSession.FACET, session)
                .with(VariableFacets.SESSION, new GuiSessionVariables(session)), viewer);
    }

    /**
     * The context a GUI's open requirement and its deny steps run against (logic.md §6.1). There is no
     * session yet, so the session variables and pages are empty, and {@code gui.args} is the typed argument
     * values the open was given. The player and the server are as for any GUI run.
     */
    public static MapLogicContext open(Viewer viewer, NamespacedId gui, Map<String, String> args) {
        var root = new LinkedHashMap<String, Object>();
        root.put("player", playerVars(viewer));
        root.put("server", serverVars());
        var guiVars = new LinkedHashMap<String, Object>();
        guiVars.put("id", gui.toString());
        guiVars.put("args", args);
        guiVars.put("session", new LinkedHashMap<String, Object>());
        guiVars.put("page", new LinkedHashMap<String, Object>());
        root.put("gui", guiVars);
        root.put("vars", new LinkedHashMap<String, Object>());
        return withPlayer(new MapLogicContext(root), viewer);
    }

    /** Attaches the player facet and placeholder resolver, when there is a player. */
    private static MapLogicContext withPlayer(MapLogicContext context, Viewer viewer) {
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
