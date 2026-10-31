package dev.materione.integrations.items;

import dev.materione.api.logic.ItemResolution;
import dev.materione.api.logic.ItemSource;
import dev.materione.api.logic.ItemSourceDescription;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.TemplateText;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import java.util.List;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * An item source backed by an optional plugin's item catalogue (logic.md §4.4): {@code hdb: 12345},
 * {@code itemsadder: ns:item}, {@code nexo: item}, {@code mmoitems: TYPE:ID}. It is registered only while its
 * plugin is enabled, so a server without the plugin never loads its classes. The provider's classes are reached
 * through {@link OptionalItemSources}' finders, which name the provider's API by its class name; a finder that
 * fails, or a provider that is not enabled, is a barrier at render, never an error at load.
 */
public final class OptionalItemSource implements ItemSource<TemplateText> {

    /** Finds the provider's item for an id, or null when it has none. Throws when the provider's API does not match. */
    @FunctionalInterface
    public interface Finder {
        ItemStack find(Plugin provider, String id) throws ReflectiveOperationException;
    }

    private final String key;
    private final String pluginName;
    private final Plugin host;
    private final Finder finder;

    /**
     * @param key the source name an author writes ({@code hdb})
     * @param pluginName the provider plugin's name as its {@code plugin.yml} declares it
     * @param host the plugin that owns the registration, whose server knows the provider
     * @param finder the lookup for the provider's API
     */
    public OptionalItemSource(String key, String pluginName, Plugin host, Finder finder) {
        this.key = key;
        this.pluginName = pluginName;
        this.host = host;
        this.finder = finder;
    }

    /** The provider plugin this source needs. */
    public String pluginName() {
        return pluginName;
    }

    @Override
    public String id() {
        return key;
    }

    @Override
    public ItemSourceDescription describe() {
        return ItemSourceDescription.of(new SelfDescription(
                pluginName + " item",
                "An item from " + pluginName + "'s catalogue, by its id; shown as a barrier when " + pluginName
                        + " is not installed",
                List.of(new ParameterSchema("id", ParameterType.STRING, true, null,
                        "The item's id in " + pluginName, null, null)),
                "1.0.0"),
                "id");
    }

    @Override
    public TemplateText parse(ParamReader params) {
        return params.template("id");
    }

    @Override
    public ItemResolution resolve(TemplateText template, LogicContext context) {
        String id = template.render(context).trim();
        Plugin provider = host.getServer().getPluginManager().getPlugin(pluginName);
        if (provider == null || !provider.isEnabled()) {
            return ItemResolution.unresolved(pluginName + " is not installed or not enabled");
        }
        try {
            ItemStack stack = finder.find(provider, id);
            return stack == null
                    ? ItemResolution.unresolved("no " + key + " item '" + id + "' in " + pluginName)
                    : ItemResolution.ok(stack.clone());
        } catch (ReflectiveOperationException | RuntimeException failed) {
            return ItemResolution.unresolved(pluginName + " could not resolve '" + id + "': "
                    + failed.getClass().getSimpleName());
        }
    }
}
