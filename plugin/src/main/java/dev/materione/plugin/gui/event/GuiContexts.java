package dev.materione.plugin.gui.event;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.Facets;
import dev.materione.api.logic.variable.StackRef;
import dev.materione.behavior.logic.MapLogicContext;
import dev.materione.plugin.gui.Viewer;
import dev.materione.plugin.view.ViewerRecord;
import dev.materione.plugin.runtime.behavior.PlaceholderResolver;
import dev.materione.plugin.logic.variable.VariableService;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.UUID;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import dev.materione.plugin.gui.session.GuiViewerState;

/**
 * Builds the context a GUI rule runs against (logic.md §2.6, §6.2): the variable tree the trigger
 * descriptions declare ({@code player.*}, {@code server.*}, {@code view.*}, {@code click.*},
 * {@code layer.id}, mutable {@code vars}) and the facets an action reaches for (the player, the
 * viewer record, and a variable binding whose session scope is this view instance). One place builds it, so every GUI
 * trigger offers the same {@code view.*} and the schema in
 * {@link dev.materione.plugin.gui.trigger.GuiTriggers} stays true.
 *
 * <p>{@code view.args} and {@code view.page} are the record's own maps, not copies: a rule that
 * writes a session value sees it on the next read, and a restored record shows what it had.
 */
public final class GuiContexts {

    private GuiContexts() {}

    /**
     * @param extra further top-level variables of the trigger ({@code click}, {@code layer}); may be empty
     */
    public static MapLogicContext build(Viewer viewer, ViewerRecord session, Map<String, Object> extra) {
        return build(viewer, session, extra, null);
    }

    public static MapLogicContext build(
            Viewer viewer, ViewerRecord session, Map<String, Object> extra, VariableService variables) {
        StackRef stack = session.stack().orElse(null);
        var root = new LinkedHashMap<String, Object>();
        root.put("player", playerVars(viewer));
        root.put("server", serverVars());
        root.put("view", viewVars(
                session.definition(), session.typedArgs(), GuiViewerState.of(session).pages(), stack));
        root.put("item", stack == null ? Map.of() : stack.itemView());
        root.put("vars", new LinkedHashMap<String, Object>());
        root.putAll(extra);
        return withPlayer(new MapLogicContext(root)
                .with(ViewerRecord.FACET, session), viewer, variables, session.instance(), stack);
    }

    /**
     * The context a GUI's open requirement and its deny steps run against (logic.md §6.1). There is no
     * viewer record yet, so the session values and pages are empty, and {@code view.args} is the argument values the open
     * was given: typed (an {@code int} an integer, a {@code number} a double) when the gate passes them so, as the
     * record's own {@code view.args} are. The player and the server are as for any GUI run.
     */
    public static MapLogicContext open(Viewer viewer, NamespacedId gui, Map<String, ?> args) {
        return open(viewer, gui, args, null);
    }

    public static MapLogicContext open(
            Viewer viewer, NamespacedId gui, Map<String, ?> args, VariableService variables) {
        return open(viewer, gui, args, variables, null);
    }

    /** The open requirement bound to the stack of the run that requested it. */
    public static MapLogicContext open(
            Viewer viewer, NamespacedId gui, Map<String, ?> args, VariableService variables, StackRef stack) {
        var root = new LinkedHashMap<String, Object>();
        root.put("player", playerVars(viewer));
        root.put("server", serverVars());
        root.put("view", viewVars(gui, args, new LinkedHashMap<String, Object>(), stack));
        root.put("item", stack == null ? Map.of() : stack.itemView());
        root.put("vars", new LinkedHashMap<String, Object>());
        return withPlayer(new MapLogicContext(root), viewer, variables, null, stack);
    }

    /**
     * The one root for view facts (views.md section 3.7): {@code view.id}, {@code view.kind}, {@code view.args.*} and, for
     * a GUI, {@code view.page.*}, and the subject stack when one bound this open. {@code view.key} remains empty
     * until keyed state binds one. {@code args} and {@code pages} are the record's own maps, not
     * copies: a rule that pages a list sees the page on the next read. A session value is {@code vars.session.*}, the
     * one spelling (views.md section 3.4).
     */
    private static Map<String, Object> viewVars(
            NamespacedId id, Map<String, ?> args, Map<String, Object> pages, StackRef stack) {
        var view = new LinkedHashMap<String, Object>();
        view.put("id", id.toString());
        view.put("kind", GuiViewerState.KIND);
        view.put("args", args);
        view.put("page", pages);
        view.put("item", stack == null ? null : stack.itemView());
        return view;
    }

    /**
     * Attaches the variable binding (with the view instance whose session variables the run reaches, or none), the
     * player facet and the placeholder resolver, when there is a player.
     */
    private static MapLogicContext withPlayer(
            MapLogicContext context, Viewer viewer, VariableService variables, UUID view, StackRef stack) {
        if (variables != null) {
            context.withVariables(variables.bind(viewer.id(), stack, view));
        }
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
