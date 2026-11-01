package dev.materione.platform.gui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.platform.PlatformDescriptor;
import java.lang.reflect.Proxy;
import java.util.Set;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.Test;

class PaperRichTitlesTest {
    @Test
    void unsupportedOrMissingSignaturesFailClosed() throws Exception {
        var unknown = PaperRichTitles.create(new PlatformDescriptor("26.1.3", false, false, false, Set.of()));
        assertThat(unknown.available()).isFalse();
        assertThat(unknown.send(null, null, Component.empty())).contains("no pinned");
        assertThat(PaperRichTitles.create(new PlatformDescriptor("1.21.4", false, false, false, Set.of()))
                .available()).isFalse(); // no server internals on the unit-test classpath
        assertThat(PaperRichTitles.create(new PlatformDescriptor("1.21.4", false, false, false, Set.of()),
                PaperRichTitlesTest::fakeClass).available()).isTrue();
        assertThat(PaperRichTitles.create(new PlatformDescriptor("26.1.2", false, false, false, Set.of()),
                PaperRichTitlesTest::fakeClass).available()).isTrue();
        assertThat(PaperRichTitles.create(new PlatformDescriptor("26.1.3", false, false, false, Set.of()),
                PaperRichTitlesTest::fakeClass).available()).isFalse();
        assertThatThrownBy(() -> PaperRichTitles.Bindings.resolve(name -> {
            throw new ClassNotFoundException(name);
        })).isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void packetKeepsNativeMenuAndResyncsContentsAndCursorWithRichFont() throws Exception {
        var server = new ServerPlayer();
        var inventory = proxy(Inventory.class, (name) -> null);
        var view = proxy(InventoryView.class, name -> name.equals("getTopInventory") ? inventory : null);
        int[] updates = {0};
        var player = proxy(FakePlayer.class, name -> switch (name) {
            case "getHandle" -> server;
            case "getOpenInventory" -> view;
            case "isOnline" -> true;
            case "updateInventory" -> { updates[0]++; yield null; }
            default -> null;
        });
        var menu = server.containerMenu;
        Component title = Component.text("overlay").font(Key.key("overlay", "gui"));
        var transport = new PaperRichTitles(PaperRichTitles.Bindings.resolve(PaperRichTitlesTest::fakeClass), null);
        assertThat(transport.send(player, inventory, title)).isNull();
        assertThat(server.containerMenu).isSameAs(menu);
        assertThat(menu.cursor).isEqualTo("cursor");
        assertThat(menu.input).isEqualTo("input");
        assertThat(server.connection.sent).isEqualTo(new OpenPacket(7, new MenuType(), new NativeTitle(title)));
        assertThat(updates[0]).isEqualTo(1);
        var other = proxy(Inventory.class, name -> null);
        assertThat(transport.send(player, other, title)).contains("no longer open");
        assertThat(updates[0]).isEqualTo(1);
    }

    private static Class<?> fakeClass(String name) throws ClassNotFoundException {
        return switch (name) {
            case "org.bukkit.craftbukkit.entity.CraftPlayer" -> FakePlayer.class;
            case "net.minecraft.server.level.ServerPlayer" -> ServerPlayer.class;
            case "net.minecraft.world.inventory.AbstractContainerMenu" -> Menu.class;
            case "net.minecraft.world.inventory.MenuType" -> MenuType.class;
            case "net.minecraft.network.chat.Component" -> NativeTitle.class;
            case "net.minecraft.network.protocol.Packet" -> Packet.class;
            case "net.minecraft.network.protocol.game.ClientboundOpenScreenPacket" -> OpenPacket.class;
            case "org.bukkit.craftbukkit.inventory.CraftContainer" -> CraftContainer.class;
            case "io.papermc.paper.adventure.PaperAdventure" -> Adventure.class;
            default -> throw new ClassNotFoundException(name);
        };
    }

    public interface FakePlayer extends Player { ServerPlayer getHandle(); }
    public static class ServerPlayer {
        public final Menu containerMenu = new Menu();
        public final Connection connection = new Connection();
    }
    public static class Menu {
        public final int containerId = 7;
        public final String cursor = "cursor";
        public final String input = "input";
    }
    public record MenuType() {}
    public record NativeTitle(Component value) {}
    public interface Packet {}
    public record OpenPacket(int id, MenuType type, NativeTitle title) implements Packet {}
    public static class Connection {
        public Packet sent;
        public void send(Packet packet) { sent = packet; }
    }
    public static class CraftContainer {
        public static MenuType getNotchInventoryType(Inventory inventory) { return new MenuType(); }
    }
    public static class Adventure {
        public static NativeTitle asVanilla(Component component) { return new NativeTitle(component); }
    }
    private interface Call { Object apply(String name); }
    private static <T> T proxy(Class<T> type, Call call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> call.apply(method.getName())));
    }
}
