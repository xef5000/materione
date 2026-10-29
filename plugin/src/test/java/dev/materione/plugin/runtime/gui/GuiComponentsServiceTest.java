package dev.materione.plugin.runtime.gui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.NamespacedId;
import dev.materione.api.gui.CompiledGuiLayer;
import dev.materione.api.gui.GuiComponent;
import dev.materione.api.gui.GuiComponentConfiguration;
import dev.materione.api.gui.RenderTarget;
import dev.materione.api.registry.RegistryException;
import dev.materione.api.registry.RegistryKeys;
import dev.materione.api.registry.SelfDescription;
import dev.materione.core.registry.RegistryService;
import java.lang.reflect.Proxy;
import java.util.function.BiFunction;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.Server;
import org.junit.jupiter.api.Test;

/**
 * The GUI component facade (gui.md §10 #7): a registration is owned by its plugin, works after the
 * registry froze, is removed by closing it or by disabling its owner, and never shadows a duplicate.
 */
class GuiComponentsServiceTest {

    private final RegistryService registries = new RegistryService();
    private final GuiComponentsService service = new GuiComponentsService(host(), registries);

    @Test
    void aRegistrationEntersTheGuiComponentsRegistryAfterItFroze() {
        var registry = registries.registry(RegistryKeys.GUI_COMPONENTS, GuiComponent.class);
        registries.freezeAll();
        var owner = plugin("addon", true);
        var registration = service.register(owner, NamespacedId.of("addon", "progress"), component("progress"));
        assertThat(registry.get(NamespacedId.of("addon", "progress"))).isPresent()
                .get().satisfies(entry -> assertThat(entry.registeredBy()).isEqualTo("addon"));
        assertThat(registration.id()).isEqualTo(NamespacedId.of("addon", "progress"));
    }

    @Test
    void closingTheHandleRemovesTheComponentAndIsIdempotent() {
        var registry = registries.registry(RegistryKeys.GUI_COMPONENTS, GuiComponent.class);
        var registration = service.register(plugin("addon", true), NamespacedId.of("addon", "bar"), component("bar"));
        registration.close();
        registration.close();
        assertThat(registry.get(NamespacedId.of("addon", "bar"))).isEmpty();
    }

    @Test
    void aDuplicateIdFailsExplicitlyAndLeavesTheFirstEntry() {
        var registry = registries.registry(RegistryKeys.GUI_COMPONENTS, GuiComponent.class);
        var first = service.register(plugin("addon", true), NamespacedId.of("addon", "dup"), component("dup"));
        assertThatThrownBy(() -> service.register(plugin("other", true), NamespacedId.of("addon", "dup"),
                component("dup")))
                .isInstanceOf(RegistryException.class)
                .hasMessageContaining("duplicate");
        assertThat(registry.get(NamespacedId.of("addon", "dup")).orElseThrow().registeredBy()).isEqualTo("addon");
        first.close();
    }

    @Test
    void theMinecraftNamespaceIsReserved() {
        assertThatThrownBy(() -> service.register(plugin("addon", true), NamespacedId.of("minecraft", "x"),
                component("x")))
                .isInstanceOf(RegistryException.class);
    }

    @Test
    void aDisabledOwnerCannotRegisterAndItsComponentsLeaveWhenItDisables() {
        var owner = plugin("addon", true);
        var registry = registries.registry(RegistryKeys.GUI_COMPONENTS, GuiComponent.class);
        service.register(owner, NamespacedId.of("addon", "gone"), component("gone"));
        var stayed = service.register(plugin("kept", true), NamespacedId.of("kept", "stay"), component("stay"));

        service.ownerDisabled(owner);
        assertThat(registry.get(NamespacedId.of("addon", "gone"))).isEmpty();
        assertThat(registry.get(NamespacedId.of("kept", "stay"))).isPresent();

        assertThatThrownBy(() -> service.register(plugin("off", false), NamespacedId.of("off", "x"), component("x")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disabled");
        stayed.close();
    }

    @Test
    void closingTheServiceRemovesEveryRegistration() {
        var registry = registries.registry(RegistryKeys.GUI_COMPONENTS, GuiComponent.class);
        service.register(plugin("addon", true), NamespacedId.of("addon", "one"), component("one"));
        service.close();
        assertThat(registry.get(NamespacedId.of("addon", "one"))).isEmpty();
        assertThatThrownBy(() -> service.register(plugin("addon", true), NamespacedId.of("addon", "two"),
                component("two")))
                .isInstanceOf(IllegalStateException.class);
    }

    private static GuiComponent component(String name) {
        return new GuiComponent() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public SelfDescription describe() {
                return SelfDescription.of(name, "test component");
            }

            @Override
            public RenderTarget target() {
                return RenderTarget.RASTER;
            }

            @Override
            public CompiledGuiLayer parse(GuiComponentConfiguration configuration) {
                throw new UnsupportedOperationException("not parsed in this test");
            }
        };
    }

    /** A plugin stub: only its name and enabled state are answered. */
    private static Plugin plugin(String name, boolean enabled) {
        return proxy(Plugin.class, (method, args) -> switch (method.getName()) {
            case "getName" -> name;
            case "isEnabled" -> enabled;
            case "hashCode" -> name.hashCode();
            case "toString" -> "plugin " + name;
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    /** The host: a server whose plugin manager accepts the listener without delivering events. */
    private static Plugin host() {
        PluginManager manager = proxy(PluginManager.class, (method, args) -> switch (method.getName()) {
            case "registerEvents" -> null;
            default -> throw new UnsupportedOperationException(method.getName());
        });
        Server server = proxy(Server.class, (method, args) -> switch (method.getName()) {
            case "getPluginManager" -> manager;
            default -> throw new UnsupportedOperationException(method.getName());
        });
        return plugin("host", true, server);
    }

    private static Plugin plugin(String name, boolean enabled, Server server) {
        return proxy(Plugin.class, (method, args) -> switch (method.getName()) {
            case "getName" -> name;
            case "isEnabled" -> enabled;
            case "getServer" -> server;
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    private static <T> T proxy(Class<T> type, BiFunction<java.lang.reflect.Method, Object[], Object> answers) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> answers.apply(method, args)));
    }
}
