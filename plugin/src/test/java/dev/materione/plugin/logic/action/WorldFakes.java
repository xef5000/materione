package dev.materione.plugin.logic.action;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.logic.RunMeter;
import dev.materione.plugin.logic.world.BlockOps;
import dev.materione.plugin.logic.world.CustomBlocks;
import dev.materione.plugin.logic.world.ProjectileLauncher;
import dev.materione.plugin.logic.world.Regions;
import dev.materione.plugin.logic.world.WorldVerbs;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.Vector;

/**
 * Stand-ins for the world verbs' tests (logic.md §41): a living player that records what is done to it, a world of blocks
 * that records what is done to them, and the platform seams (regions, block and launch paths) with scripted answers. No
 * server runs; what needs one is the harness IT and the QA catalog.
 */
final class WorldFakes {

    private WorldFakes() {}

    /** The answers a proxy gives to one method (these fakes' own, so no action class depends on another). */
    @FunctionalInterface
    interface Answer {
        Object answer(java.lang.reflect.Method method, Object[] args);
    }

    @SuppressWarnings("unchecked")
    static <T> T proxy(Class<T> type, Answer answers) {
        java.lang.reflect.InvocationHandler handler = (target, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> target == args[0];
                    case "hashCode" -> System.identityHashCode(target);
                    default -> type.getSimpleName() + " stub";
                };
            }
            return answers.answer(method, args);
        };
        return (T) java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }

    /** A living entity, as a {@link Player} (the type a run's facet holds), that records the calls the verbs make. */
    static final class Living {
        final List<String> calls = new ArrayList<>();
        final UUID uuid;
        final Location location;
        double health = 10;
        double max = 20;
        boolean dead;
        boolean valid = true;
        boolean healAccepted = true;
        Vector velocity = new Vector(0, 0, 0);
        final Player player;

        Living(UUID uuid, Location location) {
            this.uuid = uuid;
            this.location = location;
            this.player = proxy(Player.class, this::answer);
        }

        Living() {
            this(UUID.fromString("00000000-0000-0000-0000-00000000a1e7"),
                    new Location(null, 0, 64, 0, 0f, 0f));
        }

        private Object answer(java.lang.reflect.Method method, Object[] args) {
            return switch (method.getName()) {
                case "getUniqueId" -> uuid;
                case "isDead" -> dead;
                case "isValid" -> valid;
                case "getHealth" -> health;
                case "heal" -> {
                    calls.add("heal:" + args[0]);
                    if (healAccepted) {
                        health = Math.min(max, health + (Double) args[0]);
                    }
                    yield null;
                }
                case "damage" -> {
                    calls.add("damage:" + args[0] + (args.length > 1 ? ":" + describe(args[1]) : "")
                            + (dev.materione.plugin.logic.world.VerbScope.depth() > 0 ? " (in scope)" : ""));
                    yield null;
                }
                case "setVelocity" -> {
                    velocity = (Vector) args[0];
                    calls.add("velocity:" + velocity.getX() + "," + velocity.getY() + "," + velocity.getZ());
                    yield null;
                }
                case "getVelocity" -> velocity.clone();
                case "getLocation" -> location.clone();
                case "getEyeLocation" -> location.clone().add(0, 1.62, 0);
                case "getInventory" -> proxy(org.bukkit.inventory.PlayerInventory.class, (m, a) -> switch (m.getName()) {
                    case "getItemInMainHand" -> null; // an ItemStack needs a server to exist; the verb copes with none
                    default -> throw new AssertionError("unexpected inventory call: " + m.getName());
                });
                case "teleport" -> true;
                default -> throw new AssertionError("unexpected living call: " + method.getName());
            };
        }

        private static String describe(Object attacker) {
            return attacker instanceof Entity ? "entity" : String.valueOf(attacker);
        }
    }

    /** One world: blocks by position, a record of the particles and drops. */
    static final class Terrain {
        final List<String> calls = new ArrayList<>();
        final Map<String, Material> types = new HashMap<>();
        final Map<String, Block> blocks = new HashMap<>();
        final Set<String> unloadedChunks = new HashSet<>();
        final World world = proxy(World.class, this::answer);

        static String key(int x, int y, int z) {
            return x + "," + y + "," + z;
        }

        Terrain fill(int x1, int y1, int z1, int x2, int y2, int z2, Material material) {
            for (int x = x1; x <= x2; x++) {
                for (int y = y1; y <= y2; y++) {
                    for (int z = z1; z <= z2; z++) {
                        types.put(key(x, y, z), material);
                    }
                }
            }
            return this;
        }

        Material typeAt(int x, int y, int z) {
            return types.getOrDefault(key(x, y, z), Material.AIR);
        }

        Block block(int x, int y, int z) {
            return blocks.computeIfAbsent(key(x, y, z), k -> proxy(Block.class, (method, args) -> switch (method.getName()) {
                case "getType" -> typeAt(x, y, z);
                case "getX" -> x;
                case "getY" -> y;
                case "getZ" -> z;
                case "getWorld" -> world;
                case "getLocation" -> new Location(world, x, y, z);
                case "getBlockData" -> proxy(BlockData.class, (m, a) -> "data of " + key(x, y, z));
                default -> throw new AssertionError("unexpected block call: " + method.getName());
            }));
        }

        private Object answer(java.lang.reflect.Method method, Object[] args) {
            return switch (method.getName()) {
                case "getName" -> "world";
                case "isChunkLoaded" -> !unloadedChunks.contains(args[0] + "," + args[1]);
                case "getBlockAt" -> args.length == 1
                        ? block(((Location) args[0]).getBlockX(), ((Location) args[0]).getBlockY(),
                                ((Location) args[0]).getBlockZ())
                        : block((Integer) args[0], (Integer) args[1], (Integer) args[2]);
                case "spawnParticle" -> {
                    // (Particle, Location, count, offsetX, offsetY, offsetZ, extra, data)
                    Location at = (Location) args[1];
                    calls.add("particle:" + args[0] + "@" + at.getX() + "," + at.getY() + "," + at.getZ()
                            + " x" + args[2] + " spread=" + args[3] + "," + args[4] + "," + args[5]
                            + " speed=" + args[6] + " data=" + (args.length > 7 && args[7] != null
                                    ? args[7].getClass().getSimpleName() : "none"));
                    yield null;
                }
                case "dropItemNaturally" -> {
                    ItemStack stack = (ItemStack) args[1];
                    calls.add("drop:" + stack.getType() + "x" + stack.getAmount());
                    yield null;
                }
                default -> throw new AssertionError("unexpected world call: " + method.getName());
            };
        }
    }

    /** The region seam: every location is owned unless listed; a hop is recorded and runs where it is told. */
    static final class FakeRegions implements Regions {
        final Set<String> foreign = new HashSet<>();
        final List<String> hops = new ArrayList<>();
        private int hopping;

        @Override
        public boolean ownedHere(Location location) {
            // inside a hop the running thread is the owner's, as it is on a real region thread
            return hopping > 0
                    || !foreign.contains(Terrain.key(location.getBlockX(), location.getBlockY(), location.getBlockZ()));
        }

        @Override
        public <T> CompletionStage<T> at(Location location, Supplier<T> task) {
            boolean hop = !ownedHere(location);
            if (hop) {
                hops.add(Terrain.key(location.getBlockX(), location.getBlockY(), location.getBlockZ()));
                hopping++;
            }
            try {
                return CompletableFuture.completedFuture(task.get());
            } finally {
                if (hop) {
                    hopping--;
                }
            }
        }
    }

    /** The block seam: breakable unless listed, with a scripted answer per position for the "as player" path. */
    static final class FakeBlocks implements BlockOps {
        final Terrain terrain;
        final Set<String> unbreakable = new HashSet<>();
        final Set<String> protectedBlocks = new HashSet<>();
        final List<String> calls = new ArrayList<>();
        boolean mayPlace = true;
        /** Stands in for the bridges: called while a block is being broken, inside the verb's scope. */
        java.util.function.Consumer<Block> onBreak = block -> { };

        FakeBlocks(Terrain terrain) {
            this.terrain = terrain;
        }

        private static String where(Block block) {
            return Terrain.key(block.getX(), block.getY(), block.getZ());
        }

        @Override
        public boolean isAir(Block block) {
            return block.getType() == Material.AIR;
        }

        @Override
        public boolean breakable(Block block) {
            return block.getType() != Material.AIR && !unbreakable.contains(where(block));
        }

        @Override
        public Result breakAs(Player player, Block block, boolean drops) {
            calls.add("breakAs:" + where(block) + (drops ? "" : ":nodrops"));
            onBreak.accept(block);
            if (protectedBlocks.contains(where(block))) {
                return Result.DENIED;
            }
            terrain.types.remove(where(block));
            return Result.DONE;
        }

        @Override
        public Result breakPlain(Block block, boolean drops) {
            calls.add("breakPlain:" + where(block) + (drops ? "" : ":nodrops"));
            onBreak.accept(block);
            terrain.types.remove(where(block));
            return Result.DONE;
        }

        @Override
        public Result breakWith(ItemStack tool, Block block, boolean drops) {
            calls.add("breakWith:" + where(block) + (drops ? "" : ":nodrops"));
            onBreak.accept(block);
            terrain.types.remove(where(block));
            return Result.DONE;
        }

        @Override
        public boolean mayPlace(Player player, Block block, BlockData data) {
            calls.add("mayPlace:" + where(block));
            return mayPlace;
        }

        @Override
        public Result place(Block block, BlockData data) {
            calls.add("place:" + where(block));
            terrain.types.put(where(block), Material.STONE);
            return Result.DONE;
        }
    }

    /** The launch seam: knows a few projectile names, records each launch with the item id it was given. */
    static final class FakeLauncher implements ProjectileLauncher {
        final List<String> launches = new ArrayList<>();
        boolean refuse;

        @Override
        public Optional<EntityType> projectile(String key) {
            return switch (key) {
                case "snowball" -> Optional.of(EntityType.SNOWBALL);
                case "small_fireball" -> Optional.of(EntityType.SMALL_FIREBALL);
                default -> Optional.empty();
            };
        }

        @Override
        public Optional<Entity> launch(ProjectileSource shooter, EntityType type, Vector velocity, Optional<String> itemId) {
            launches.add(type + " speed=" + Math.round(velocity.length() * 100) / 100.0 + " item=" + itemId.orElse("-"));
            return refuse ? Optional.empty() : Optional.of(proxy(Entity.class, (m, a) -> null));
        }
    }

    /** A custom-block runtime that knows one block. */
    static final class FakeCustomBlocks implements CustomBlocks {
        final List<String> calls = new ArrayList<>();

        @Override
        public boolean exists(dev.materione.api.NamespacedId id) {
            return id.toString().equals("pack:ruby_ore");
        }

        @Override
        public boolean place(Block block, dev.materione.api.NamespacedId id) {
            calls.add("place:" + id);
            return exists(id);
        }

        @Override
        public boolean remove(Block block, boolean drop) {
            return false;
        }
    }

    /**
     * A stand-in protection provider (protection.md): denies the places and the victims a test lists, abstains for the rest, and
     * records every question as {@code ACTION@x,y,z by <actor>} so a test sees who was asked and with what actor.
     */
    static final class FakePort implements dev.materione.api.protection.ProtectionPort {
        final Set<String> denied = new HashSet<>();
        final List<String> asked = new ArrayList<>();
        /** Whether an allowed place answers allow (true) or abstain (false). */
        boolean allowRest = false;

        private static String who(dev.materione.api.protection.ProtectionActor actor) {
            return actor.isNone() ? "nobody" : actor.online().isPresent() ? "player" : "offline";
        }

        private dev.materione.api.protection.ProtectionAnswer answer(String action,
                dev.materione.api.protection.ProtectionActor actor, Location at) {
            String where = Terrain.key(at.getBlockX(), at.getBlockY(), at.getBlockZ());
            asked.add(action + "@" + where + " by " + who(actor));
            if (denied.contains(where) || denied.contains(action + "@" + where)) {
                return dev.materione.api.protection.ProtectionAnswer.deny("Stand-in", "the stand-in says no");
            }
            return allowRest ? dev.materione.api.protection.ProtectionAnswer.allow("Stand-in")
                    : dev.materione.api.protection.ProtectionAnswer.abstain("Stand-in");
        }

        @Override
        public String name() {
            return "Stand-in";
        }

        @Override
        public dev.materione.api.registry.SelfDescription describe() {
            return dev.materione.api.registry.SelfDescription.of("Stand-in", "A test provider");
        }

        @Override
        public dev.materione.api.protection.ProtectionAnswer canBreak(
                dev.materione.api.protection.ProtectionActor actor, Location location) {
            return answer("BREAK", actor, location);
        }

        @Override
        public dev.materione.api.protection.ProtectionAnswer canPlace(
                dev.materione.api.protection.ProtectionActor actor, Location location) {
            return answer("PLACE", actor, location);
        }

        @Override
        public dev.materione.api.protection.ProtectionAnswer canInteract(
                dev.materione.api.protection.ProtectionActor actor, Location location) {
            return answer("INTERACT", actor, location);
        }

        @Override
        public dev.materione.api.protection.ProtectionAnswer canDamage(
                dev.materione.api.protection.ProtectionActor attacker, Entity victim) {
            return answer("DAMAGE", attacker, victim.getLocation());
        }

        @Override
        public dev.materione.api.protection.ProtectionAnswer canUse(
                dev.materione.api.protection.ProtectionActor actor, Location location) {
            return answer("USE", actor, location);
        }
    }

    /** A plugin stand-in that is enabled, for registering a provider. */
    static org.bukkit.plugin.Plugin enabledPlugin() {
        return proxy(org.bukkit.plugin.Plugin.class, (method, args) -> switch (method.getName()) {
            case "isEnabled" -> true;
            case "getName" -> "TestPlugin";
            default -> throw new AssertionError("unexpected plugin call: " + method.getName());
        });
    }

    /** The platform of one test, with every seam scripted. */
    static final class Platform {
        final Terrain terrain = new Terrain();
        final FakeRegions regions = new FakeRegions();
        final FakeBlocks blocks = new FakeBlocks(terrain);
        final FakeLauncher launcher = new FakeLauncher();
        final List<Diagnostic> diagnostics = new ArrayList<>();
        final dev.materione.plugin.protection.ProtectionService protection;
        long now = 1_000_000;
        final WorldVerbs verbs;

        /** A platform whose protection is the event path alone (the shape before the port). */
        Platform() {
            this(dev.materione.plugin.protection.ProtectionSettings.EVENT_ONLY);
        }

        /** A platform whose protection runs on {@code settings}; register providers on {@link #protection}. */
        Platform(dev.materione.plugin.protection.ProtectionSettings settings) {
            this.protection = new dev.materione.plugin.protection.ProtectionService(settings, regions, false,
                    diagnostics::add, () -> now,
                    id -> proxy(org.bukkit.OfflinePlayer.class, (method, args) -> switch (method.getName()) {
                        case "getUniqueId" -> id;
                        default -> throw new AssertionError("unexpected offline player call: " + method.getName());
                    }));
            this.verbs = new WorldVerbs(regions, blocks, launcher,
                    name -> name.equals("world") ? terrain.world : null,
                    key -> key.getKey().equals("cloud") ? Particle.CLOUD : key.getKey().equals("dust") ? Particle.DUST : null,
                    text -> {
                        if (text.contains("nonsense")) {
                            throw new IllegalArgumentException(text);
                        }
                        return proxy(BlockData.class, (m, a) -> text);
                    },
                    dev.materione.plugin.logic.world.DamageWatch.NONE,
                    dev.materione.plugin.logic.world.WorldLimits.DEFAULTS, diagnostics::add, () -> now, protection);
        }
    }

    /** A run meter for a test: totals by key. */
    static final class Meter implements RunMeter {
        final Map<String, Long> totals = new HashMap<>();
        Object origin;

        @Override
        public Object origin() {
            return origin;
        }

        @Override
        public long add(String key, long amount) {
            return totals.merge(key, amount, Long::sum);
        }

        @Override
        public long total(String key) {
            return totals.getOrDefault(key, 0L);
        }
    }
}
