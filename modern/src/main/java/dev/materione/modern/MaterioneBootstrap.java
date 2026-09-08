package dev.materione.modern;

import dev.materione.modern.BootstrapContentReader.RegistryContent;
import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;

/**
 * The plugin's bootstrap phase (runtime-systems §10): on servers whose
 * registry lifecycle supports it (1.21.6+), painting variants and jukebox
 * songs register through Paper's registry compose events — no world
 * datapack is deployed, matching the Nexo model. On the 1.21.4/1.21.5
 * floor the registry seam is absent and everything rides the compiled
 * world datapack as before.
 *
 * <p>All work is fail-soft: any crash here logs
 * {@code MATERIONE-REGISTRY-FAILED} and the server continues on the
 * datapack path — a bootstrap failure would otherwise take the whole
 * server down before plugins load.
 */
public final class MaterioneBootstrap implements PluginBootstrap {

    @Override
    public void bootstrap(BootstrapContext context) {
        try {
            if (!RegistryCapabilities.hasFullRegistrySeam()) {
                // floor server: paintings + songs ride the world datapack
                context.getLogger().info(
                        "MATERIONE-REGISTRY-OK paintings=0 songs=0 mode=datapack");
                return;
            }
            // before vanilla scans the world datapacks: a leftover artifact
            // would register its entries first and every compose registration
            // of the same keys would fail as a duplicate
            WorldDatapackCleanup.removeDeployed(
                    context.getDataDirectory(),
                    warning -> context.getLogger().info(warning));
            RegistryContent content = BootstrapContentReader.read(
                    context.getDataDirectory(), warning -> context.getLogger().warn(warning));
            PaintingRegistrar.register(context, content.paintings());
            int songs = SongRegistrar.register(context, content.songs());
            context.getLogger().info("MATERIONE-REGISTRY-OK paintings=" + content.paintings().size()
                    + " songs=" + songs + " mode=registry");
        } catch (RuntimeException e) {
            context.getLogger().error("MATERIONE-REGISTRY-FAILED registry registration crashed", e);
        }
    }
}
