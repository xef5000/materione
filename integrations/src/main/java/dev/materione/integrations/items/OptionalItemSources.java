package dev.materione.integrations.items;

import java.lang.reflect.Method;
import java.util.List;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * The four optional item catalogues (logic.md §4.4): HeadDatabase, ItemsAdder, Nexo and MMOItems. Each finder
 * reaches its provider by class name through the provider's own class loader, so none of those jars is a
 * compile-time dependency and none is loaded unless its plugin runs.
 *
 * <p><b>Verification status.</b> These call paths are not compiled or exercised in this build: the four APIs
 * are not pinned in the version catalogue. A call that does not match the installed provider throws, and that
 * surfaces as a barrier with the reason in the window (MTR-0072), so a mismatch is visible, not silent. Pinning
 * the provider jars and replacing the reflection with direct calls is the follow-up, once the jars are verified.
 */
public final class OptionalItemSources {

    /** The provider plugins by the names their {@code plugin.yml} declares; a change to one of them refreshes the gate. */
    public static final List<String> PLUGINS = List.of("HeadDatabase", "ItemsAdder", "Nexo", "MMOItems");

    private OptionalItemSources() {}

    /** The sources, one per provider plugin, for {@link dev.materione.integrations.Integrations} to register while each runs. */
    public static List<OptionalItemSource> all(Plugin host) {
        return List.of(
                new OptionalItemSource("hdb", "HeadDatabase", host, OptionalItemSources::headDatabase),
                new OptionalItemSource("itemsadder", "ItemsAdder", host, OptionalItemSources::itemsAdder),
                new OptionalItemSource("nexo", "Nexo", host, OptionalItemSources::nexo),
                new OptionalItemSource("mmoitems", "MMOItems", host, OptionalItemSources::mmoItems));
    }

    /** {@code me.arcaniax.hdb.api.HeadDatabaseAPI#getItemHead(String)}: a head by its database id. */
    static ItemStack headDatabase(Plugin provider, String id) throws ReflectiveOperationException {
        Class<?> api = Class.forName("me.arcaniax.hdb.api.HeadDatabaseAPI", true, loaderOf(provider));
        Object instance = api.getDeclaredConstructor().newInstance();
        return (ItemStack) api.getMethod("getItemHead", String.class).invoke(instance, id);
    }

    /** {@code dev.lone.itemsadder.api.CustomStack.getInstance(id).getItemStack()}. */
    static ItemStack itemsAdder(Plugin provider, String id) throws ReflectiveOperationException {
        Class<?> stack = Class.forName("dev.lone.itemsadder.api.CustomStack", true, loaderOf(provider));
        Object custom = stack.getMethod("getInstance", String.class).invoke(null, id);
        return custom == null ? null : (ItemStack) custom.getClass().getMethod("getItemStack").invoke(custom);
    }

    /** {@code com.nexomc.nexo.api.NexoItems.itemFromId(id).build()}. */
    static ItemStack nexo(Plugin provider, String id) throws ReflectiveOperationException {
        Class<?> items = Class.forName("com.nexomc.nexo.api.NexoItems", true, loaderOf(provider));
        Object builder = items.getMethod("itemFromId", String.class).invoke(null, id);
        return builder == null ? null : (ItemStack) builder.getClass().getMethod("build").invoke(builder);
    }

    /** {@code MMOItems.plugin.getItem(type, id)} for an id written {@code TYPE:ID}. */
    static ItemStack mmoItems(Plugin provider, String id) throws ReflectiveOperationException {
        int colon = id.indexOf(':');
        if (colon <= 0 || colon == id.length() - 1) {
            throw new IllegalArgumentException("MMOItems ids are written TYPE:ID");
        }
        ClassLoader loader = loaderOf(provider);
        Class<?> mmo = Class.forName("net.Indyuce.mmoitems.MMOItems", true, loader);
        Object plugin = mmo.getField("plugin").get(null);
        Object types = plugin.getClass().getMethod("getTypes").invoke(plugin);
        Object type = types.getClass().getMethod("get", String.class).invoke(types, id.substring(0, colon));
        if (type == null) {
            return null;
        }
        Class<?> typeClass = Class.forName("net.Indyuce.mmoitems.api.Type", true, loader);
        Method getItem = plugin.getClass().getMethod("getItem", typeClass, String.class);
        return (ItemStack) getItem.invoke(plugin, type, id.substring(colon + 1));
    }

    private static ClassLoader loaderOf(Plugin provider) {
        return provider.getClass().getClassLoader();
    }
}
