package dev.materione.plugin.runtime.behavior;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.DiagnosticsBus;
import dev.materione.api.diagnostics.Severity;
import dev.materione.behavior.rule.EventTypes;
import dev.materione.platform.entity.EntityPalette;
import dev.materione.plugin.runtime.behavior.WorldEvents.Fire;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.entity.Damageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;

/**
 * The firing sites of the world-event triggers (logic.md §37): {@code entity.hit}, {@code entity.kill},
 * {@code player.hurt}, {@code player.join}, {@code player.quit}, {@code player.death} and
 * {@code player.respawn}. Listeners are static wiring, registered once; rules come from the live shadow at
 * event entry, so a reload needs no listener churn.
 *
 * <p><b>A trigger nobody answers costs a set lookup.</b> Every handler's first line asks
 * {@link WorldEvents#subscribes}; when no item, block, furniture or rule document has a rule on the trigger
 * it returns before it reads the event, resolves a stack or builds a context. This is why the hot events
 * (damage fires for every mob in the world) are safe to listen to.
 *
 * <p>On Folia the damage and death events fire on the victim's region. A player in another region is not
 * read (their inventory belongs to their own thread), so such an event fires no rule for them; the common
 * case, a fight inside one region, is unaffected.
 *
 * <p>Runs at HIGH like the item bridge: protection plugins decide first, and an event they cancelled fires
 * nothing. {@code player.quit} runs early (LOW) so a rule starts before the logic service ends the player's
 * parked runs.
 */
public final class WorldEventListener implements Listener {

    private final WorldEvents events;
    private final DiagnosticsBus diagnostics;

    public WorldEventListener(WorldEvents events, DiagnosticsBus diagnostics) {
        this.events = events;
        this.diagnostics = diagnostics;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (!events.subscribes(EventTypes.ENTITY_HIT)) {
            return;
        }
        Player attacker = attackerOf(event.getDamager());
        Entity target = event.getEntity();
        if (attacker == null || attacker == target || !ownedHere(attacker)) {
            return;
        }
        try {
            events.fire(new Fire(EventTypes.ENTITY_HIT, attacker, target::getLocation,
                    Map.of("type", EntityPalette.typeOf(target), "cause", EntityPalette.causeOf(event.getCause())),
                    () -> Map.of("target", target(target, true), "damage", damage(event)), event,
                    () -> List.of(attacker.getInventory().getItemInMainHand()), true));
        } catch (RuntimeException e) {
            reportBridgeFailure(attacker, e);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHurt(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player) || !events.subscribes(EventTypes.PLAYER_HURT)
                || !ownedHere(player)) {
            return;
        }
        try {
            events.fire(new Fire(EventTypes.PLAYER_HURT, player, player::getLocation,
                    Map.of("cause", EntityPalette.causeOf(event.getCause())),
                    () -> {
                        Entity source = event instanceof EntityDamageByEntityEvent byEntity
                                ? responsible(byEntity.getDamager()) : null;
                        return Map.of("damage", damage(event),
                                "source", Map.of("type", source == null ? "" : EntityPalette.typeOf(source)));
                    },
                    event, () -> heldAndWorn(player), true));
        } catch (RuntimeException e) {
            reportBridgeFailure(player, e);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onKill(EntityDeathEvent event) {
        if (!events.subscribes(EventTypes.ENTITY_KILL)) {
            return;
        }
        LivingEntity dead = event.getEntity();
        Player killer = dead.getKiller();
        if (killer == null || killer == dead || !ownedHere(killer)) {
            return;
        }
        try {
            events.fire(new Fire(EventTypes.ENTITY_KILL, killer, dead::getLocation,
                    Map.of("type", EntityPalette.typeOf(dead)), () -> Map.of("target", target(dead, false)), null,
                    () -> List.of(killer.getInventory().getItemInMainHand()), true));
        } catch (RuntimeException e) {
            reportBridgeFailure(killer, e);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent event) {
        if (!events.subscribes(EventTypes.PLAYER_JOIN)) {
            return;
        }
        Player player = event.getPlayer();
        try {
            events.fire(new Fire(EventTypes.PLAYER_JOIN, player, player::getLocation, Map.of(),
                    () -> Map.of("player", Map.of("first_join", !player.hasPlayedBefore())), null, null, false));
        } catch (RuntimeException e) {
            reportBridgeFailure(player, e);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onQuit(PlayerQuitEvent event) {
        if (!events.subscribes(EventTypes.PLAYER_QUIT)) {
            return;
        }
        Player player = event.getPlayer();
        try {
            events.fire(new Fire(EventTypes.PLAYER_QUIT, player, player::getLocation, Map.of(), null, null, null,
                    false));
        } catch (RuntimeException e) {
            reportBridgeFailure(player, e);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        if (!events.subscribes(EventTypes.PLAYER_DEATH)) {
            return;
        }
        Player player = event.getEntity();
        try {
            events.fire(new Fire(EventTypes.PLAYER_DEATH, player, player::getLocation, Map.of(), () -> {
                EntityDamageEvent last = player.getLastDamageCause();
                return Map.of("damage", Map.of("cause", EntityPalette.causeOf(last == null ? null : last.getCause())));
            }, null, null, false));
        } catch (RuntimeException e) {
            reportBridgeFailure(player, e);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        if (!events.subscribes(EventTypes.PLAYER_RESPAWN)) {
            return;
        }
        Player player = event.getPlayer();
        try {
            events.fire(new Fire(EventTypes.PLAYER_RESPAWN, player, event::getRespawnLocation, Map.of(), null, null,
                    null, false));
        } catch (RuntimeException e) {
            reportBridgeFailure(player, e);
        }
    }

    // --- conversion -----------------------------------------------------------------

    /** The player who dealt the damage: the damager itself, or the shooter of a projectile. */
    static Player attackerOf(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            return player;
        }
        return null;
    }

    /** The entity responsible for damage: the shooter of a projectile when it was an entity, else the damager. */
    static Entity responsible(Entity damager) {
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
            return shooter;
        }
        return damager;
    }

    /** The stacks a hurt player's item rules can hang on: the main hand, then each piece of armor worn. */
    private static List<ItemStack> heldAndWorn(Player player) {
        var hosts = new ArrayList<ItemStack>();
        hosts.add(player.getInventory().getItemInMainHand());
        for (ItemStack worn : player.getInventory().getArmorContents()) {
            if (worn != null && !worn.getType().isAir()) {
                hosts.add(worn);
            }
        }
        return hosts;
    }

    private static Map<String, Object> target(Entity entity, boolean withHealth) {
        var values = new LinkedHashMap<String, Object>();
        values.put("type", EntityPalette.typeOf(entity));
        values.put("uuid", entity.getUniqueId().toString());
        values.put("is_player", entity instanceof Player);
        if (withHealth) {
            values.put("health", entity instanceof Damageable damageable ? damageable.getHealth() : 0.0);
        }
        return values;
    }

    private static Map<String, Object> damage(EntityDamageEvent event) {
        var values = new LinkedHashMap<String, Object>();
        values.put("amount", event.getDamage());
        values.put("final", event.getFinalDamage());
        values.put("cause", EntityPalette.causeOf(event.getCause()));
        return values;
    }

    /** Whether this thread may read the player: always on Paper, the player's own region on Folia. */
    private static boolean ownedHere(Player player) {
        return Bukkit.isOwnedByCurrentRegion(player);
    }

    private void reportBridgeFailure(Player player, RuntimeException e) {
        diagnostics.publish(new Diagnostic(
                DiagnosticCodes.ACTION_FAILED, Severity.WARN,
                "world event bridge failed for " + player.getName() + ": " + e.getMessage(),
                null, null, "the event continues vanilla handling"));
    }
}
