package dev.materione.harness.probe;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Test-only scaffolding for gui-input-durability.md section 11/12, row R (#306): a <b>fake-connection player</b>, a
 * <b>crash gate</b> and the primitives the K-row skeleton IT (and later row H, #313) are built from. It is not product
 * code and never ships; it is built by {@code :harness:durabilityProbeJar} and installed by the harness IT.
 *
 * <p>The fake player is a real {@code ServerPlayer} placed by the server's own {@code PlayerList.placeNewPlayer} over a
 * {@code Connection} whose channel is a netty {@code EmbeddedChannel}: no socket, no client, but the same join, save,
 * inventory-menu, packet-handler and close paths a real player takes. Everything NMS is reached by reflection, as
 * {@code ContainerProbe} does, because the plugin compiles against the API alone.
 *
 * <pre>
 *   dprobe join &lt;name&gt;                         place a fake player (offline UUID, so a restart finds its .dat)
 *   dprobe leave &lt;name&gt;                        PlayerList.remove: close container, quit event, then save (F4)
 *   dprobe state &lt;name&gt;                        one line: online, health, gamemode, container, cursor
 *   dprobe set &lt;name&gt; &lt;slot&gt; &lt;MATERIAL&gt; &lt;n&gt;   put a stack into an inventory slot (n = 0 clears)
 *   dprobe inv &lt;name&gt;                          the inventory, the cursor, the probe account and the pdc marker
 *   dprobe pdc &lt;name&gt; &lt;key&gt; [value]            read or write a string in the player's persistent data
 *   dprobe movein &lt;name&gt; &lt;slot&gt;               tier-1 analog: inventory slot -&gt; probe account, one step, gen++
 *   dprobe moveout &lt;name&gt;                      tier-1 analog: newest account entry -&gt; inventory, one step, gen++
 *   dprobe save &lt;name&gt;                         Player#saveData, synchronous, on the calling (main) thread
 *   dprobe gate &lt;name&gt;                         log the crash-gate marker, then park this thread forever
 *   dprobe open &lt;name&gt;                         open a 27-slot window owned by the probe
 *   dprobe win &lt;name&gt; &lt;slot&gt; &lt;MATERIAL&gt; &lt;n&gt;     put a stack into a slot of the open window
 *   dprobe click &lt;name&gt; &lt;rawSlot&gt; &lt;button&gt; &lt;PICKUP|QUICK_MOVE|SWAP|...&gt;   a ServerboundContainerClickPacket
 *   dprobe drag &lt;name&gt; &lt;0|1&gt; &lt;rawSlot,rawSlot,...&gt;   the three quick-craft packets of a drag
 *   dprobe close &lt;name&gt;                        Player#closeInventory
 *   dprobe mode &lt;none|close|event&gt;             how the death scenario releases the window (see below)
 *   dprobe die &lt;name&gt; / respawn &lt;name&gt;        setHealth(0) / PERFORM_RESPAWN
 *   dprobe items                                dropped item entities in the first world
 * </pre>
 *
 * Every answer is one log line containing {@code MATERIONE-DPROBE}. The crash gate parks the calling thread, which is the
 * main thread for a console command: nothing ticks, so no autosave can run after the marker, and the harness kills the
 * JVM while the server is exactly in that state.
 */
public final class DurabilityProbe extends JavaPlugin implements Listener {

    private static final String PREFIX = "MATERIONE-DPROBE ";

    /** The window's identity, as {@code PluginGui} is for the product. */
    static final class ProbeHolder implements InventoryHolder {
        Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private NamespacedKey accountKey;
    private NamespacedKey genKey;
    private final AtomicInteger ports = new AtomicInteger(40000);
    /** How the death scenario releases a window's stacks: none, on the close the death triggers, or in the death event. */
    private volatile String mode = "none";
    private final Map<UUID, Object> channels = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        accountKey = new NamespacedKey(this, "account");
        genKey = new NamespacedKey(this, "gen");
        Bukkit.getPluginManager().registerEvents(this, this);
        log("ready");
    }

    private void log(String line) {
        getLogger().info(PREFIX + line);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String stage = args.length == 0 ? "" : args[0];
        try {
            switch (stage) {
                case "join" -> join(args[1]);
                case "leave" -> leave(args[1]);
                case "state" -> state(player(args[1]));
                case "set" -> setSlot(player(args[1]), Integer.parseInt(args[2]), args[3], Integer.parseInt(args[4]));
                case "inv" -> inv(player(args[1]));
                case "pdc" -> pdc(player(args[1]), args[2], args.length > 3 ? args[3] : null);
                case "movein" -> moveIn(player(args[1]), Integer.parseInt(args[2]));
                case "moveout" -> moveOut(player(args[1]));
                case "save" -> save(player(args[1]));
                case "gate" -> gate(args[1]);
                case "open" -> open(player(args[1]));
                case "win" -> win(player(args[1]), Integer.parseInt(args[2]), args[3], Integer.parseInt(args[4]));
                case "click" -> click(player(args[1]), Integer.parseInt(args[2]), Integer.parseInt(args[3]), args[4]);
                case "drag" -> drag(player(args[1]), Integer.parseInt(args[2]), args[3]);
                case "close" -> close(player(args[1]));
                case "mode" -> {
                    mode = args[1];
                    log("mode ok=true mode=" + mode);
                }
                case "die" -> die(player(args[1]));
                case "respawn" -> respawn(player(args[1]));
                case "items" -> items();
                default -> throw new IllegalArgumentException("unknown stage '" + stage + "'");
            }
        } catch (Throwable failure) {
            Throwable cause = failure instanceof InvocationTargetException ite && ite.getCause() != null
                    ? ite.getCause() : failure;
            getLogger().severe(PREFIX + stage + " ok=false error=" + cause);
            cause.printStackTrace();
        }
        return true;
    }

    // ---- the fake-connection player -------------------------------------------------------------------------------

    private void join(String name) throws Exception {
        if (Bukkit.getPlayerExact(name) != null) {
            throw new IllegalStateException(name + " is already online");
        }
        UUID id = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        Object server = R.call(Bukkit.getServer(), "getServer");
        Object level = R.call(Bukkit.getWorlds().get(0), "getHandle");
        Object profile = R.create(R.type("com.mojang.authlib.GameProfile"), id, name);
        Object information = R.callStatic(R.type("net.minecraft.server.level.ClientInformation"), "createDefault");
        Object handle = R.create(R.type("net.minecraft.server.level.ServerPlayer"), server, level, profile, information);

        // A Connection over an EmbeddedChannel: no socket. The pipeline holds only the Connection itself, so a clientbound
        // packet the server "sends" is queued on the channel (and drained below) instead of serialised.
        Object flow = R.type("net.minecraft.network.protocol.PacketFlow").getField("SERVERBOUND").get(null);
        Object connection = R.create(R.type("net.minecraft.network.Connection"), flow);
        Object channel = R.create(R.type("io.netty.channel.embedded.EmbeddedChannel"));
        Object pipeline = R.call(channel, "pipeline");
        R.call(pipeline, "addLast", "packet_handler", connection);
        R.call(pipeline, "fireChannelActive");
        R.field(connection, "address").set(connection, new InetSocketAddress("127.0.0.1", ports.incrementAndGet()));
        channels.put(id, channel);

        Object cookie = R.callStatic(R.type("net.minecraft.server.network.CommonListenerCookie"), "createInitial", profile, false);
        Object playerList = R.call(server, "getPlayerList");
        R.call(playerList, "placeNewPlayer", connection, handle, cookie);
        drain(id);
        Player player = Bukkit.getPlayer(id);
        log("join ok=" + (player != null) + " player=" + name + " uuid=" + id
                + " health=" + (player == null ? "n/a" : player.getHealth()));
    }

    /** Clientbound packets pile up on the embedded channel; drop them so a long test does not hold every chunk. */
    private void drain(UUID id) throws Exception {
        Object channel = channels.get(id);
        if (channel != null) {
            ((Collection<?>) R.call(channel, "outboundMessages")).clear();
        }
    }

    private void leave(String name) throws Exception {
        Player player = player(name);
        Object server = R.call(Bukkit.getServer(), "getServer");
        Object playerList = R.call(server, "getPlayerList");
        R.call(playerList, "remove", handle(player));
        channels.remove(player.getUniqueId());
        log("leave ok=" + (Bukkit.getPlayerExact(name) == null) + " player=" + name);
    }

    private Player player(String name) {
        Player player = Bukkit.getPlayerExact(name);
        if (player == null) {
            throw new IllegalStateException(name + " is not online");
        }
        return player;
    }

    private static Object handle(Player player) throws Exception {
        return R.call(player, "getHandle");
    }

    private void state(Player player) throws Exception {
        Object menu = R.field(handle(player), "containerMenu").get(handle(player));
        log("state player=" + player.getName() + " online=" + player.isOnline() + " health=" + player.getHealth()
                + " gamemode=" + player.getGameMode() + " container=" + player.getOpenInventory().getType()
                + " menuId=" + R.field(menu, "containerId").get(menu)
                + " cursor=" + describe(player.getItemOnCursor())
                + " dead=" + player.isDead());
    }

    // ---- inventory, persistent data, the account analog -----------------------------------------------------------

    private void setSlot(Player player, int slot, String material, int amount) {
        player.getInventory().setItem(slot, amount <= 0 ? null : new ItemStack(Material.valueOf(material), amount));
        log("set ok=true player=" + player.getName() + " slot=" + slot);
    }

    private void inv(Player player) {
        StringBuilder out = new StringBuilder();
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (contents[slot] != null && !contents[slot].getType().isAir()) {
                out.append(slot).append(':').append(describe(contents[slot])).append(',');
            }
        }
        log("inv player=" + player.getName() + " slots=[" + out + "] cursor=" + describe(player.getItemOnCursor())
                + " account=" + describeAccount(player) + " gen=" + gen(player));
    }

    private String describeAccount(Player player) {
        byte[] bytes = player.getPersistentDataContainer().get(accountKey, PersistentDataType.BYTE_ARRAY);
        if (bytes == null) {
            return "none";
        }
        StringBuilder out = new StringBuilder("[");
        for (ItemStack stack : ItemStack.deserializeItemsFromBytes(bytes)) {
            out.append(describe(stack)).append(',');
        }
        return out.append(']').toString();
    }

    private long gen(Player player) {
        Long value = player.getPersistentDataContainer().get(genKey, PersistentDataType.LONG);
        return value == null ? 0 : value;
    }

    private void pdc(Player player, String key, String value) {
        NamespacedKey namespaced = new NamespacedKey(this, key);
        if (value != null) {
            player.getPersistentDataContainer().set(namespaced, PersistentDataType.STRING, value);
        }
        log("pdc ok=true player=" + player.getName() + " key=" + key + " value="
                + player.getPersistentDataContainer().get(namespaced, PersistentDataType.STRING));
    }

    /** One owner-thread step: the stack leaves the inventory and enters the account, in the same call (I1). */
    private void moveIn(Player player, int slot) {
        ItemStack stack = player.getInventory().getItem(slot);
        if (stack == null || stack.getType().isAir()) {
            throw new IllegalStateException("slot " + slot + " is empty");
        }
        List<ItemStack> entries = new ArrayList<>();
        byte[] bytes = player.getPersistentDataContainer().get(accountKey, PersistentDataType.BYTE_ARRAY);
        if (bytes != null) {
            entries.addAll(List.of(ItemStack.deserializeItemsFromBytes(bytes)));
        }
        entries.add(stack.clone());
        player.getInventory().setItem(slot, null);
        player.getPersistentDataContainer().set(accountKey, PersistentDataType.BYTE_ARRAY,
                ItemStack.serializeItemsAsBytes(entries));
        player.getPersistentDataContainer().set(genKey, PersistentDataType.LONG, gen(player) + 1);
        log("movein ok=true player=" + player.getName() + " gen=" + gen(player) + " entries=" + entries.size());
    }

    private void moveOut(Player player) {
        byte[] bytes = player.getPersistentDataContainer().get(accountKey, PersistentDataType.BYTE_ARRAY);
        if (bytes == null) {
            throw new IllegalStateException("no account");
        }
        List<ItemStack> entries = new ArrayList<>(List.of(ItemStack.deserializeItemsFromBytes(bytes)));
        ItemStack stack = entries.remove(entries.size() - 1);
        Map<Integer, ItemStack> left = player.getInventory().addItem(stack);
        entries.addAll(left.values());
        player.getPersistentDataContainer().set(accountKey, PersistentDataType.BYTE_ARRAY,
                ItemStack.serializeItemsAsBytes(entries));
        player.getPersistentDataContainer().set(genKey, PersistentDataType.LONG, gen(player) + 1);
        log("moveout ok=true player=" + player.getName() + " gen=" + gen(player) + " entries=" + entries.size());
    }

    private void save(Player player) {
        long started = System.nanoTime();
        player.saveData();
        log("save ok=true player=" + player.getName() + " thread=" + Thread.currentThread().getName()
                + " micros=" + (System.nanoTime() - started) / 1000);
    }

    // ---- the crash gate -------------------------------------------------------------------------------------------

    /**
     * The named crash gate. In production code (row P) this is a point behind {@code -Dmaterione.escrow.gate=<name>}; here
     * it is a command, so a K-row decides exactly where the server stops. The marker is logged first, then the calling
     * thread parks: no further tick runs, so nothing saves behind the harness's back, and a kill lands in a known state.
     */
    private void gate(String name) {
        log("gate reached=" + name);
        // A log4j appender flushes on its own thread; give the line time to reach latest.log before the kill
        try {
            Thread.sleep(250);
            while (true) {
                Thread.sleep(60_000);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- windows, clicks, drags -----------------------------------------------------------------------------------

    private void open(Player player) {
        ProbeHolder holder = new ProbeHolder();
        Inventory inventory = Bukkit.createInventory(holder, 27, Component.text("Probe window"));
        holder.inventory = inventory;
        player.openInventory(inventory);
        log("open ok=" + (player.getOpenInventory().getTopInventory() == inventory) + " player=" + player.getName()
                + " size=" + inventory.getSize());
    }

    private void win(Player player, int slot, String material, int amount) {
        player.getOpenInventory().getTopInventory().setItem(slot,
                amount <= 0 ? null : new ItemStack(Material.valueOf(material), amount));
        log("win ok=true player=" + player.getName() + " slot=" + slot);
    }

    private void close(Player player) {
        player.closeInventory();
        log("close ok=true player=" + player.getName());
    }

    /** A ServerboundContainerClickPacket handled by the player's own packet listener: the path a real click takes. */
    private void click(Player player, int rawSlot, int button, String type) throws Exception {
        sendClick(player, rawSlot, button, type);
        log("click ok=true player=" + player.getName() + " raw=" + rawSlot + " button=" + button + " type=" + type
                + " cursor=" + describe(player.getItemOnCursor()));
    }

    private void drag(Player player, int dragType, String slots) throws Exception {
        int type = dragType << 2;
        sendClick(player, -999, type, "QUICK_CRAFT");
        for (String slot : slots.split(",")) {
            sendClick(player, Integer.parseInt(slot.trim()), 1 | type, "QUICK_CRAFT");
        }
        sendClick(player, -999, 2 | type, "QUICK_CRAFT");
        log("drag ok=true player=" + player.getName() + " type=" + dragType + " slots=" + slots
                + " cursor=" + describe(player.getItemOnCursor()));
    }

    private void sendClick(Player player, int rawSlot, int button, String type) throws Exception {
        Object handle = handle(player);
        Object menu = R.field(handle, "containerMenu").get(handle);
        int containerId = (int) R.field(menu, "containerId").get(menu);
        int stateId = (int) R.call(menu, "getStateId");
        Object carried = R.call(menu, "getCarried");
        Class<?> clickType = R.type("net.minecraft.world.inventory.ClickType");
        Object changed = R.create(R.type("it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap"));
        Object packet = R.create(R.type("net.minecraft.network.protocol.game.ServerboundContainerClickPacket"),
                containerId, stateId, rawSlot, button, clickType.getField(type).get(null), carried, changed);
        Object connection = R.field(handle, "connection").get(handle);
        R.call(connection, "handleContainerClick", packet);
    }

    // ---- death (P3) -----------------------------------------------------------------------------------------------

    private void die(Player player) {
        player.setHealth(0);
        log("die ok=true player=" + player.getName() + " dead=" + player.isDead());
    }

    private void respawn(Player player) {
        player.spigot().respawn();
        Player now = Bukkit.getPlayerExact(player.getName());
        log("respawn ok=" + (now != null && !now.isDead()) + " player=" + player.getName()
                + " sameObject=" + (now == player));
    }

    private void items() {
        World world = Bukkit.getWorlds().get(0);
        StringBuilder out = new StringBuilder();
        for (Item item : world.getEntitiesByClass(Item.class)) {
            out.append(describe(item.getItemStack())).append('@')
                    .append(item.getLocation().getBlockX()).append('/').append(item.getLocation().getBlockZ()).append(',');
        }
        log("items count=" + world.getEntitiesByClass(Item.class).size() + " items=[" + out + "]");
    }

    @EventHandler
    public void died(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        log("event death player=" + player.getName() + " keepInventory=" + event.getKeepInventory()
                + " keepLevel=" + event.getKeepLevel() + " dropsBefore=" + describe(event.getDrops())
                + " inventoryBefore=" + describe(java.util.Arrays.asList(player.getInventory().getContents())));
        if (mode.equals("event")) {
            Inventory window = player.getOpenInventory().getTopInventory();
            if (window.getHolder() instanceof ProbeHolder) {
                List<ItemStack> held = new ArrayList<>();
                for (ItemStack stack : window.getContents()) {
                    if (stack != null && !stack.getType().isAir()) {
                        held.add(stack.clone());
                    }
                }
                window.clear();
                if (event.getKeepInventory()) {
                    for (ItemStack stack : held) {
                        player.getInventory().addItem(stack);
                    }
                } else {
                    event.getDrops().addAll(held);
                }
                log("event death released=" + describe(held) + " to=" + (event.getKeepInventory() ? "inventory" : "drops"));
            }
        }
        log("event death dropsAfter=" + describe(event.getDrops()));
    }

    @EventHandler
    public void respawned(PlayerRespawnEvent event) {
        log("event respawn player=" + event.getPlayer().getName() + " inventoryAtEvent="
                + describe(java.util.Arrays.asList(event.getPlayer().getInventory().getContents())));
    }

    // ---- observation ----------------------------------------------------------------------------------------------

    @EventHandler
    public void clicked(InventoryClickEvent event) {
        log("event click player=" + event.getWhoClicked().getName() + " raw=" + event.getRawSlot()
                + " slot=" + event.getSlot() + " click=" + event.getClick() + " action=" + event.getAction()
                + " slotType=" + event.getSlotType() + " holder="
                + (event.getView().getTopInventory().getHolder() instanceof ProbeHolder ? "probe" : "other")
                + " thread=" + Thread.currentThread().getName());
    }

    @EventHandler
    public void dragged(InventoryDragEvent event) {
        log("event drag player=" + event.getWhoClicked().getName() + " rawSlots=" + event.getRawSlots()
                + " type=" + event.getType() + " cursorAfter=" + describe(event.getCursor())
                + " thread=" + Thread.currentThread().getName());
    }

    @EventHandler
    public void closed(InventoryCloseEvent event) {
        Player player = (Player) event.getPlayer();
        Inventory top = event.getView().getTopInventory();
        boolean probe = top.getHolder() instanceof ProbeHolder;
        log("event close player=" + player.getName() + " reason=" + event.getReason() + " holder="
                + (probe ? "probe" : "other") + " window=" + describe(java.util.Arrays.asList(top.getContents()))
                + " inventoryAtClose=" + describe(java.util.Arrays.asList(player.getInventory().getContents())));
        if (probe && mode.equals("close")) {
            // The shipped behaviour (GuiJournal release): the window's stacks go to the inventory on the close
            for (ItemStack stack : top.getContents()) {
                if (stack != null && !stack.getType().isAir()) {
                    player.getInventory().addItem(stack.clone());
                }
            }
            top.clear();
            log("event close released-to-inventory player=" + player.getName()
                    + " inventoryAfterRelease=" + describe(java.util.Arrays.asList(player.getInventory().getContents())));
        }
    }

    @EventHandler
    public void joined(PlayerJoinEvent event) {
        log("event join player=" + event.getPlayer().getName() + " inventory="
                + describe(java.util.Arrays.asList(event.getPlayer().getInventory().getContents()))
                + " account=" + describeAccount(event.getPlayer()) + " gen=" + gen(event.getPlayer()));
    }

    @EventHandler
    public void quit(PlayerQuitEvent event) {
        log("event quit player=" + event.getPlayer().getName());
    }

    private static String describe(ItemStack stack) {
        return stack == null || stack.getType().isAir() ? "empty" : stack.getType() + "*" + stack.getAmount();
    }

    private static String describe(Collection<ItemStack> stacks) {
        StringBuilder out = new StringBuilder("[");
        for (ItemStack stack : stacks) {
            if (stack != null && !stack.getType().isAir()) {
                out.append(describe(stack)).append(',');
            }
        }
        return out.append(']').toString();
    }

    /** Minimal reflection: public members, or declared ones made accessible, matched by name and argument types. */
    static final class R {
        private R() {}

        static Class<?> type(String name) throws ClassNotFoundException {
            return Class.forName(name);
        }

        static Object create(Class<?> type, Object... args) throws Exception {
            for (Constructor<?> constructor : type.getConstructors()) {
                if (matches(constructor.getParameterTypes(), args)) {
                    return constructor.newInstance(args);
                }
            }
            throw new NoSuchMethodException(type.getName() + " constructor for " + args.length + " args");
        }

        static Object call(Object target, String name, Object... args) throws Exception {
            return invoke(target.getClass(), target, name, args);
        }

        static Object callStatic(Class<?> type, String name, Object... args) throws Exception {
            return invoke(type, null, name, args);
        }

        private static Object invoke(Class<?> type, Object target, String name, Object[] args) throws Exception {
            for (Class<?> c = type; c != null; c = c.getSuperclass()) {
                List<Method> candidates = new ArrayList<>(List.of(c.getDeclaredMethods()));
                if (c == type) {
                    candidates.addAll(List.of(c.getMethods()));
                }
                for (Method method : candidates) {
                    if (method.getName().equals(name) && matches(method.getParameterTypes(), args)
                            && (target != null || Modifier.isStatic(method.getModifiers()))) {
                        method.setAccessible(true);
                        return method.invoke(target, args);
                    }
                }
            }
            for (Method method : type.getMethods()) {
                if (method.getName().equals(name) && matches(method.getParameterTypes(), args)) {
                    method.setAccessible(true);
                    return method.invoke(target, args);
                }
            }
            throw new NoSuchMethodException(type.getName() + "#" + name + " for " + args.length + " args");
        }

        static Field field(Object target, String name) throws Exception {
            for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
                try {
                    Field field = c.getDeclaredField(name);
                    field.setAccessible(true);
                    return field;
                } catch (NoSuchFieldException next) {
                    // keep climbing
                }
            }
            throw new NoSuchFieldException(target.getClass().getName() + "#" + name);
        }

        private static boolean matches(Class<?>[] parameters, Object[] args) {
            if (parameters.length != args.length) {
                return false;
            }
            for (int i = 0; i < parameters.length; i++) {
                if (!boxed(parameters[i]).isInstance(args[i])) {
                    return false;
                }
            }
            return true;
        }

        private static Class<?> boxed(Class<?> type) {
            if (!type.isPrimitive()) {
                return type;
            }
            return switch (type.getName()) {
                case "int" -> Integer.class;
                case "long" -> Long.class;
                case "boolean" -> Boolean.class;
                case "double" -> Double.class;
                case "float" -> Float.class;
                case "short" -> Short.class;
                case "byte" -> Byte.class;
                default -> Character.class;
            };
        }
    }
}
