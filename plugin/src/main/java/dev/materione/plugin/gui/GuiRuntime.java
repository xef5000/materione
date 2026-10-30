package dev.materione.plugin.gui;

import dev.materione.api.ApiResult;
import dev.materione.api.NamespacedId;
import dev.materione.api.auth.ActorContext;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.runtime.RuntimeApi;
import dev.materione.api.scheduling.SchedulingGateway;
import dev.materione.core.runtime.RuntimeRegistries;
import dev.materione.plugin.gui.open.OpenRequest;
import dev.materione.plugin.gui.open.OpenResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * The runtime API for GUIs (gui.md §5): the catalog, and opening a GUI for a player. It is the
 * {@link RuntimeApi.GuiRuntimeApi} implementation and a thin facade: it checks the caller and hops to the
 * viewer's thread, then hands the open to the {@link GuiStack}'s opener, the one open path, so the
 * command, the API and the editor's preview open a GUI exactly as a flow's {@code gui.open} does.
 *
 * <p>Opening validates on the caller's thread (permission, online player, GUI live) and refuses at
 * once; the window is built and opened on the viewer's region thread (D8). The live refresh after a
 * swap belongs to the stack ({@link #refreshAfterSwap} forwards to it).
 */
public final class GuiRuntime implements RuntimeApi.GuiRuntimeApi {

    /** The node that opens a GUI for a player (master §9.2 taxonomy). */
    public static final String OPEN_PERMISSION = "materione.gui.open";

    private final Plugin plugin;
    private final SchedulingGateway scheduling;
    private final Supplier<RuntimeRegistries> live;
    private final GuiStack stack;

    /**
     * A runtime with no GUI stack: it lists GUIs and refuses to open any. For hosts and tests that
     * only read the catalog.
     */
    public GuiRuntime(Plugin plugin, SchedulingGateway scheduling, Supplier<RuntimeRegistries> live) {
        this(plugin, scheduling, live, null);
    }

    /**
     * @param plugin the owning plugin (region scheduling and logging); a server-less host may pass
     *     null, since only the window and refresh paths use it, and those need a server anyway
     * @param scheduling the thread gateway (D8)
     * @param live the current live registries; null or missing {@link RuntimeGuis} means no GUIs
     * @param stack the GUI runtime that opens windows and keeps sessions; null opens nothing
     */
    public GuiRuntime(Plugin plugin, SchedulingGateway scheduling, Supplier<RuntimeRegistries> live,
            GuiStack stack) {
        this.plugin = plugin;
        this.scheduling = Objects.requireNonNull(scheduling, "scheduling");
        this.live = Objects.requireNonNull(live, "live");
        this.stack = stack;
    }

    // --- catalog ------------------------------------------------------------

    @Override
    public List<RuntimeApi.GuiView> guis() {
        var out = new ArrayList<RuntimeApi.GuiView>();
        for (RuntimeGuis.Live gui : viewOf(live.get()).all()) {
            out.add(viewOf(gui));
        }
        return out;
    }

    @Override
    public Optional<RuntimeApi.GuiView> gui(NamespacedId id) {
        return viewOf(live.get()).gui(id).map(GuiRuntime::viewOf);
    }

    @Override
    public Optional<String> onlineName(UUID player) {
        Player online = onlinePlayer(player);
        return online == null ? Optional.empty() : Optional.ofNullable(online.getName());
    }

    // --- open -----------------------------------------------------------------

    @Override
    public CompletableFuture<ApiResult<Void>> open(ActorContext actor, NamespacedId guiId, UUID targetPlayer) {
        return open(actor, guiId, targetPlayer, Map.of());
    }

    @Override
    public CompletableFuture<ApiResult<Void>> open(
            ActorContext actor, NamespacedId guiId, UUID targetPlayer, Map<String, String> args) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(guiId, "guiId");
        Objects.requireNonNull(args, "args");
        CompletableFuture<ApiResult<Void>> future = new CompletableFuture<>();
        if (!actor.hasPermission(OPEN_PERMISSION)) {
            future.complete(ApiResult.fail(DiagnosticCodes.PERMISSION_DENIED,
                    "no permission (" + OPEN_PERMISSION + ")"));
            return future;
        }
        Player player = onlinePlayer(targetPlayer);
        if (player == null) {
            future.complete(ApiResult.fail(DiagnosticCodes.UNRESOLVED_REFERENCE, "player is not online"));
            return future;
        }
        if (viewOf(live.get()).gui(guiId).isEmpty()) {
            future.complete(ApiResult.fail(DiagnosticCodes.UNRESOLVED_REFERENCE,
                    "GUI '" + guiId + "' is not live: it is unknown, or its last compile quarantined it"));
            return future;
        }
        if (stack == null) {
            future.complete(ApiResult.fail(DiagnosticCodes.PIPELINE_STAGE_FAILED,
                    "GUI open failed: the GUI runtime is not running"));
            return future;
        }
        try {
            scheduling.executeEntity(plugin, player, () -> {
                try {
                    if (!player.isOnline()) {
                        future.complete(ApiResult.fail(DiagnosticCodes.UNRESOLVED_REFERENCE, "player is not online"));
                        return;
                    }
                    future.complete(answer(stack.opener().open(
                            OpenRequest.of(Viewer.of(player), guiId, args, OpenRequest.Cause.API))));
                } catch (RuntimeException e) {
                    future.complete(ApiResult.fail(DiagnosticCodes.PIPELINE_STAGE_FAILED,
                            "GUI open failed: " + e.getMessage()));
                }
            });
        } catch (RuntimeException e) {
            future.complete(ApiResult.fail(DiagnosticCodes.PIPELINE_STAGE_FAILED,
                    "GUI open could not be scheduled: " + e.getMessage()));
        }
        return future;
    }

    /** The opener's answer as an API result: each refusal keeps its own code and reason. */
    private static ApiResult<Void> answer(OpenResult result) {
        return switch (result.outcome()) {
            case OPENED -> ApiResult.ok(null);
            case NOT_LIVE -> ApiResult.fail(DiagnosticCodes.UNRESOLVED_REFERENCE, result.reason());
            case DENIED -> ApiResult.fail(DiagnosticCodes.PERMISSION_DENIED, result.reason());
            case CANCELLED, FAILED ->
                    ApiResult.fail(DiagnosticCodes.PIPELINE_STAGE_FAILED, "GUI open failed: " + result.reason());
        };
    }

    // --- live refresh ---------------------------------------------------------

    /**
     * Live refresh (gui.md §5): called on the global thread after a successful swap. The GUIs this
     * swap changed or removed are pushed to their viewers, whose sessions carry on; every other window
     * is left alone.
     *
     * @param previous the registries the swap replaced
     * @param current the registries the swap published
     */
    public void refreshAfterSwap(RuntimeRegistries previous, RuntimeRegistries current) {
        if (stack != null) {
            stack.refresh().afterSwap(previous, current);
        }
    }

    // --- helpers --------------------------------------------------------------

    private static RuntimeGuis viewOf(RuntimeRegistries registries) {
        RuntimeGuis view = registries == null ? null : registries.view(RuntimeGuis.KEY);
        return view == null ? RuntimeGuis.empty() : view;
    }

    private static RuntimeApi.GuiView viewOf(RuntimeGuis.Live gui) {
        return new RuntimeApi.GuiView(gui.id(), gui.rows(), gui.accessibleTitle(), gui.slots().size());
    }

    /** Player lookup that fails soft when no server is up (tests, early bootstrap). */
    private static Player onlinePlayer(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        try {
            return Bukkit.getPlayer(uuid);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
