package dev.materione.platform.gui;

import dev.materione.api.platform.PlatformDescriptor;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Set;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

/**
 * Pinned Paper title transport. The server internals are resolved once, inside the platform boundary,
 * and a missing or changed signature makes the capability unavailable rather than reopening a window.
 */
public final class PaperRichTitles implements RichTitleTransport {
    private static final Set<String> VERSIONS = Set.of("1.21.4", "26.1.2");
    private final Bindings bindings;
    private final String problem;

    PaperRichTitles(Bindings bindings, String problem) {
        this.bindings = bindings;
        this.problem = problem;
    }

    public static PaperRichTitles create(PlatformDescriptor descriptor) {
        ClassLoader loader = PaperRichTitles.class.getClassLoader();
        return create(descriptor, name -> Class.forName(name, false, loader));
    }

    static PaperRichTitles create(PlatformDescriptor descriptor, Classes classes) {
        if (descriptor == null || !VERSIONS.contains(descriptor.minecraftVersion())) {
            return new PaperRichTitles(null, "no pinned rich-title transport for this server version");
        }
        try {
            return new PaperRichTitles(Bindings.resolve(classes), null);
        } catch (ReflectiveOperationException | LinkageError | SecurityException failure) {
            return new PaperRichTitles(null, "Paper rich-title signatures unavailable: " + failure);
        }
    }

    @Override
    public boolean available() {
        return bindings != null;
    }

    @Override
    public String send(Player player, Inventory expected, Component title) {
        if (bindings == null) {
            return problem;
        }
        if (player == null || !player.isOnline() || player.getOpenInventory().getTopInventory() != expected) {
            return "the expected inventory is no longer open";
        }
        try {
            Object handle = bindings.handle.invoke(player);
            Object menu = bindings.menu.get(handle);
            int id = bindings.id.getInt(menu);
            Object type = bindings.menuType.invoke(null, expected);
            Object nativeTitle = bindings.title.invoke(null, title);
            Object packet = bindings.packet.newInstance(id, type, nativeTitle);
            Object connection = bindings.connection.get(handle);
            bindings.send.invoke(connection, packet);
            player.updateInventory();
            return null;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            return "Paper rich-title send or inventory resynchronization failed: " + failure;
        }
    }

    @FunctionalInterface
    interface Classes {
        Class<?> load(String name) throws ClassNotFoundException;
    }

    record Bindings(Method handle, Field menu, Field id, Method menuType, Method title,
            Constructor<?> packet, Field connection, Method send) {
        static Bindings resolve(Classes classes) throws ReflectiveOperationException {
            Class<?> craftPlayer = classes.load("org.bukkit.craftbukkit.entity.CraftPlayer");
            Class<?> serverPlayer = classes.load("net.minecraft.server.level.ServerPlayer");
            Class<?> menu = classes.load("net.minecraft.world.inventory.AbstractContainerMenu");
            Class<?> type = classes.load("net.minecraft.world.inventory.MenuType");
            Class<?> nativeComponent = classes.load("net.minecraft.network.chat.Component");
            Class<?> packet = classes.load("net.minecraft.network.protocol.Packet");
            Class<?> open = classes.load("net.minecraft.network.protocol.game.ClientboundOpenScreenPacket");
            Class<?> craftContainer = classes.load("org.bukkit.craftbukkit.inventory.CraftContainer");
            Class<?> adventure = classes.load("io.papermc.paper.adventure.PaperAdventure");
            Field connection = serverPlayer.getField("connection");
            Method handle = craftPlayer.getMethod("getHandle");
            Field currentMenu = serverPlayer.getField("containerMenu");
            Field id = menu.getField("containerId");
            Method menuType = craftContainer.getMethod("getNotchInventoryType", Inventory.class);
            Method title = adventure.getMethod("asVanilla", Component.class);
            if (!serverPlayer.isAssignableFrom(handle.getReturnType()) || !menu.isAssignableFrom(currentMenu.getType())
                    || id.getType() != int.class || !type.isAssignableFrom(menuType.getReturnType())
                    || !Modifier.isStatic(menuType.getModifiers()) || !Modifier.isStatic(title.getModifiers())
                    || !nativeComponent.isAssignableFrom(title.getReturnType()) || !packet.isAssignableFrom(open)) {
                throw new NoSuchMethodException("Paper rich-title signature mismatch");
            }
            return new Bindings(handle, currentMenu, id, menuType, title,
                    open.getConstructor(int.class, type, nativeComponent), connection,
                    connection.getType().getMethod("send", packet));
        }
    }
}
