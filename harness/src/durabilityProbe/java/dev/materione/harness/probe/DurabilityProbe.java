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
 *   dprobe login &lt;name&gt;                        row H: fire AsyncPlayerPreLoginEvent by hand (the product preloads the ledger), then join
 *   dprobe inv &lt;name&gt;                          the inventory, the cursor and the product's escrow account (hex)
 *   dprobe pdc &lt;name&gt; &lt;key&gt; [value]            read or write a string in the player's persistent data
 *   dprobe getbytes &lt;name&gt; &lt;key&gt;               the player's byte[] value of Materione's key, as hex ("none" when absent)
 *   dprobe putbytes &lt;name&gt; &lt;key&gt; &lt;hex&gt;        write that value (used to craft a state the product cannot make yet: EXITING)
 *   dprobe adopt &lt;name&gt; &lt;hex&gt;             commit an account into the product's live PlayerEscrow (memory and data together)
 *   dprobe fill &lt;name&gt; &lt;MATERIAL&gt; &lt;n&gt;          put n of a material in all 36 storage slots (an inventory with no room)
 *   dprobe cmd &lt;name&gt; &lt;command...&gt;             Player#performCommand, as the player typed it
 *   dprobe arm &lt;gate&gt;                          set the production crash gate's armed name (-Dmaterione.escrow.gate must be set)
 *   dprobe disable                              disable the Materione plugin (onDisable runs, as on /reload or a stop)
 *   dprobe save &lt;name&gt;                         Player#saveData, synchronous, on the calling (main) thread
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
 * Every answer is one log line containing {@code MATERIONE-DPROBE}. The crash gate is the product's own
 * ({@code CrashGate}, {@code -Dmaterione.escrow.gate}, logs {@code MATERIONE-ESCROW-GATE <name>} and parks the thread it
 * is reached on); the probe only arms it and triggers the step that reaches it. Row R's {@code dprobe gate} and
 * persistent-data account stand-in are gone: row H replaced them with the product's.
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

    /** The product's account key (row P): the probe reads and writes it only as bytes, never decoding it. */
    private static final String ESCROW_NAMESPACE = "materione";
    private static final String GATE_PROPERTY = "materione.escrow.gate";

    private final AtomicInteger ports = new AtomicInteger(40000);
    /** How the death scenario releases a window's stacks: none, on the close the death triggers, or in the death event. */
    private volatile String mode = "none";
    private final Map<UUID, Object> channels = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
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
                case "login" -> login(args[1]);
                case "getbytes" -> getBytes(player(args[1]), args[2]);
                case "putbytes" -> putBytes(player(args[1]), args[2], args[3]);
                case "adopt" -> adopt(player(args[1]), args[2]);
                case "fill" -> fill(player(args[1]), args[2], Integer.parseInt(args[3]));
                case "cmd" -> command(player(args[1]), String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)));
                case "arm" -> arm(args[1]);
                case "disable" -> disableProduct();
                case "save" -> save(player(args[1]));
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
        StringBuilder window = new StringBuilder();
        Inventory top = player.getOpenInventory().getTopInventory();
        if (top.getType() != org.bukkit.event.inventory.InventoryType.CRAFTING) {
            for (int slot = 0; slot < top.getSize(); slot++) {
                ItemStack stack = top.getItem(slot);
                if (stack != null && !stack.getType().isAir()) {
                    window.append(slot).append(':').append(describe(stack)).append(',');
                }
            }
        }
        log("inv player=" + player.getName() + " slots=[" + out + "] cursor=" + describe(player.getItemOnCursor())
                + " window=[" + window + "] escrow=" + hex(player, ESCROW_ACCOUNT));
    }

    private static final String ESCROW_ACCOUNT = "gui_escrow";

    private static String hex(Player player, String key) {
        byte[] bytes = player.getPersistentDataContainer().get(
                NamespacedKey.fromString(ESCROW_NAMESPACE + ":" + key), PersistentDataType.BYTE_ARRAY);
        return bytes == null ? "none" : java.util.HexFormat.of().formatHex(bytes);
    }

    private void getBytes(Player player, String key) {
        log("getbytes ok=true player=" + player.getName() + " key=" + key + " value=" + hex(player, key));
    }

    private void putBytes(Player player, String key, String hex) {
        player.getPersistentDataContainer().set(NamespacedKey.fromString(ESCROW_NAMESPACE + ":" + key),
                PersistentDataType.BYTE_ARRAY, java.util.HexFormat.of().parseHex(hex));
        log("putbytes ok=true player=" + player.getName() + " key=" + key + " bytes=" + hex.length() / 2);
    }

    /**
     * Tier 2 has no caller before #248, so a row that needs an EXITING entry <em>in the product's memory</em> (not only
     * in the saved data) has this: the account is decoded by the product's own codec and committed through the player's
     * own {@code PlayerEscrow} (the live account and the persistent value change together, as in any step), by
     * reflection across the plugin boundary. Never a product path; the probe is not shipped.
     */
    private void adopt(Player player, String hex) throws Exception {
        org.bukkit.plugin.Plugin product = Bukkit.getPluginManager().getPlugin("Materione");
        if (product == null) {
            throw new IllegalStateException("Materione is not installed");
        }
        ClassLoader loader = product.getClass().getClassLoader();
        byte[] bytes = java.util.HexFormat.of().parseHex(hex);
        Object read = R.callStatic(loader.loadClass("dev.materione.plugin.escrow.model.AccountCodec"), "decode", bytes);
        Object account = R.call(read, "account");
        Object runtime = R.call(product, "escrowRuntime");
        Object escrow = R.call(runtime, "of", player);
        R.call(escrow, "commit", account, bytes);
        log("adopt ok=true player=" + player.getName() + " bytes=" + bytes.length);
    }

    private void fill(Player player, String material, int amount) {
        for (int slot = 0; slot < 36; slot++) {
            player.getInventory().setItem(slot, new ItemStack(Material.valueOf(material), amount));
        }
        log("fill ok=true player=" + player.getName() + " material=" + material);
    }

    private void command(Player player, String command) {
        boolean ran = player.performCommand(command);
        log("cmd ok=" + ran + " player=" + player.getName() + " command=" + command);
    }

    /** Re-arms the product's crash gate from inside the server: the gate reads the property's current value. */
    private void arm(String gate) {
        if (System.getProperty(GATE_PROPERTY) == null) {
            throw new IllegalStateException("the server was not started with -D" + GATE_PROPERTY);
        }
        System.setProperty(GATE_PROPERTY, gate);
        log("arm ok=true gate=" + gate);
    }

    private void disableProduct() {
        org.bukkit.plugin.Plugin product = Bukkit.getPluginManager().getPlugin("Materione");
        if (product == null) {
            throw new IllegalStateException("Materione is not installed");
        }
        Bukkit.getPluginManager().disablePlugin(product);
        log("disable ok=" + !product.isEnabled());
    }

    /**
     * The login a fake connection does not have: the server's pre-login event, fired by hand from a plain thread (it is an
     * asynchronous event, F9), so the product's own listener preloads the player's ledger exactly as it does for a real
     * login; the join follows. The calling (main) thread waits for the event, as the login thread would.
     */
    @SuppressWarnings("removal")
    private void login(String name) throws Exception {
        UUID id = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        // The product waits at most 5 s for the preload and never refuses the login (a timeout leaves the recovery gate
        // closed, which a real client sees as "still loading"). A host so loaded that the first wait times out is
        // not what a row is about, so the login is retried as a client would until the ledger image is there.
        for (int attempt = 1; attempt <= 4; attempt++) {
            var event = new org.bukkit.event.player.AsyncPlayerPreLoginEvent(name,
                    java.net.InetAddress.getLoopbackAddress(), id);
            Thread async = new Thread(() -> Bukkit.getPluginManager().callEvent(event), "dprobe-prelogin");
            async.start();
            async.join(30_000);
            log("login prelogin result=" + event.getLoginResult() + " player=" + name + " attempt=" + attempt);
            if (preloaded(id)) {
                break;
            }
        }
        join(name);
    }

    /** Whether the product's ledger store holds a preloaded image for the player (it answers empty until the read ends). */
    private static boolean preloaded(UUID id) throws Exception {
        org.bukkit.plugin.Plugin product = Bukkit.getPluginManager().getPlugin("Materione");
        Object store = product == null ? null : R.call(product, "escrowLedger");
        return store == null || ((java.util.Optional<?>) R.call(store, "preloaded", id)).isPresent();
    }

    private void pdc(Player player, String key, String value) {
        NamespacedKey namespaced = new NamespacedKey(this, key);
        if (value != null) {
            player.getPersistentDataContainer().set(namespaced, PersistentDataType.STRING, value);
        }
        log("pdc ok=true player=" + player.getName() + " key=" + key + " value="
                + player.getPersistentDataContainer().get(namespaced, PersistentDataType.STRING));
    }

    private void save(Player player) {
        long started = System.nanoTime();
        player.saveData();
        log("save ok=true player=" + player.getName() + " thread=" + Thread.currentThread().getName()
                + " micros=" + (System.nanoTime() - started) / 1000);
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
                + " escrow=" + hex(event.getPlayer(), ESCROW_ACCOUNT));
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
