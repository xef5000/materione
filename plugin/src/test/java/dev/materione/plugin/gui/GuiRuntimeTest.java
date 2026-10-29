package dev.materione.plugin.gui;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.ApiResult;
import dev.materione.api.NamespacedId;
import dev.materione.api.auth.ActorContext;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.runtime.RuntimeApi;
import dev.materione.api.scheduling.SchedulingGateway;
import dev.materione.content.gui.CompiledGui;
import dev.materione.core.runtime.RuntimeRegistries;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

/**
 * {@link GuiRuntime} without a server: the refusals that happen before any window is built (the
 * permission node, an offline player), the catalog over the live view, and the refresh path that
 * does nothing when a swap changed no GUI.
 */
class GuiRuntimeTest {

    private static final NamespacedId SHOP = NamespacedId.parse("demo:shop");

    @Test
    void openRefusesAnActorWithoutTheNodeBeforeAnythingElse() {
        GuiRuntime runtime = runtimeWith(liveShop());
        ActorContext without = ActorContext.player(UUID.randomUUID(), "Probe", node -> false);

        ApiResult<Void> result = runtime.open(without, SHOP, UUID.randomUUID()).join();

        assertThat(result.ok()).isFalse();
        assertThat(result.diagnostics()).extracting(d -> d.code())
                .containsExactly(DiagnosticCodes.PERMISSION_DENIED);
    }

    @Test
    void openFailsSoftForAnOfflinePlayerWithoutTouchingTheServer() {
        GuiRuntime runtime = runtimeWith(liveShop());
        ActorContext allowed = ActorContext.console();

        ApiResult<Void> result = runtime.open(allowed, SHOP, UUID.randomUUID()).join();

        assertThat(result.ok()).isFalse();
        assertThat(result.diagnostics()).extracting(d -> d.code())
                .containsExactly(DiagnosticCodes.UNRESOLVED_REFERENCE);
        assertThat(result.diagnostics().get(0).message()).contains("not online");
    }

    @Test
    void theCatalogListsOnlyWhatIsLive() {
        GuiRuntime runtime = runtimeWith(liveShop());

        // the fixture's sword is not live, so its slot is left empty: no filled slots
        List<RuntimeApi.GuiView> guis = runtime.guis();
        assertThat(guis).containsExactly(new RuntimeApi.GuiView(SHOP, 3, "Shop", 0));
        assertThat(runtime.gui(SHOP)).contains(new RuntimeApi.GuiView(SHOP, 3, "Shop", 0));
        assertThat(runtime.gui(NamespacedId.parse("demo:missing"))).isEmpty();
    }

    @Test
    void noLiveRegistriesMeansNoGuis() {
        GuiRuntime runtime = new GuiRuntime(plugin(), gateway(), () -> null);

        assertThat(runtime.guis()).isEmpty();
        assertThat(runtime.gui(SHOP)).isEmpty();
    }

    @Test
    void aSwapThatChangedNoGuiDoesNotTouchTheServer() {
        RuntimeRegistries same = registries(liveShop());
        GuiRuntime runtime = new GuiRuntime(plugin(), gateway(), () -> same);

        // Bukkit has no server here: reaching for online players would throw, so this must return first
        runtime.refreshAfterSwap(same, same);
    }

    // --- fixtures -----------------------------------------------------------

    private static CompiledGui liveShop() {
        return new CompiledGui(SHOP, 3, "<font:demo:gui>Shop", "Shop",
                List.of(new CompiledGui.Slot(4, NamespacedId.parse("demo:sword"), 1)));
    }

    /** A registry set carrying one live GUI view, built the way the shadow builder builds it. */
    private static RuntimeRegistries registries(CompiledGui gui) {
        var models = new java.util.LinkedHashMap<NamespacedId, CompiledGui>();
        models.put(gui.id(), gui);
        // the sword is not live in this view, so the slot is reported and left empty
        RuntimeGuis view = RuntimeGuis.build(models, id -> Optional.empty(), id -> Optional.empty(),
                new ArrayList<>()::add);
        return RuntimeRegistries.builder().view(RuntimeGuis.KEY, view).build();
    }

    private static GuiRuntime runtimeWith(CompiledGui gui) {
        RuntimeRegistries registries = registries(gui);
        return new GuiRuntime(plugin(), gateway(), () -> registries);
    }

    /** A plugin stand-in for paths that never reach the plugin; any call into it is a bug. */
    private static Plugin plugin() {
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class},
                (proxy, method, args) -> {
                    throw new UnsupportedOperationException("plugin." + method.getName() + " in a server-less test");
                });
    }

    private static SchedulingGateway gateway() {
        return new SchedulingGateway() {
            @Override
            public void executeSync(Plugin plugin, Location location, Runnable task) {
                task.run();
            }

            @Override
            public void executeEntity(Plugin plugin, Entity entity, Runnable task) {
                task.run();
            }

            @Override
            public void executeAsync(Plugin plugin, Runnable task) {
                task.run();
            }

            @Override
            public void executeGlobal(Plugin plugin, Runnable task) {
                task.run();
            }
        };
    }
}
