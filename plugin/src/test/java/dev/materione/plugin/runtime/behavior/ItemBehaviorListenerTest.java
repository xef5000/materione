package dev.materione.plugin.runtime.behavior;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.Test;

class ItemBehaviorListenerTest {

    @Test
    void preCancelledAirIsNotAProtectionVetoButCancelledBlockInteractionIs() {
        Player player = proxyPlayer();
        var air = new PlayerInteractEvent(
                player, Action.RIGHT_CLICK_AIR, null, null, null, EquipmentSlot.HAND);
        var block = new PlayerInteractEvent(
                player, Action.RIGHT_CLICK_BLOCK, null, null, null, EquipmentSlot.HAND);
        air.setCancelled(true);
        block.setCancelled(true);

        assertThat(ItemBehaviorListener.blockInteractionCancelled(air)).isFalse();
        assertThat(ItemBehaviorListener.blockInteractionCancelled(block)).isTrue();
    }

    @Test
    void interactHandlerDoesNotFilterPreCancelledAirEvents() throws NoSuchMethodException {
        var handler = ItemBehaviorListener.class
                .getMethod("onInteract", PlayerInteractEvent.class)
                .getAnnotation(EventHandler.class);

        assertThat(handler).isNotNull();
        assertThat(handler.ignoreCancelled()).isFalse();
    }

    private static Player proxyPlayer() {
        return Player.class.cast(Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, args) -> null));
    }
}
