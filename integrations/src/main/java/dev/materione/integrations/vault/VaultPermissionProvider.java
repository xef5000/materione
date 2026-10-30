package dev.materione.integrations.vault;

import dev.materione.api.logic.provider.PermissionProvider;
import dev.materione.api.logic.provider.ProviderResult;
import java.util.UUID;
import net.milkbowl.vault.permission.Permission;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

/**
 * Permission nodes through Vault's permission service, which the installed permission plugin
 * (PermissionsEx, GroupManager, and so on) registers (logic.md §4.5). Grants are global: Vault's
 * world argument is passed as {@code null}, which every Vault backend reads as "all worlds".
 * Temporary grants are not offered; Vault has no expiry, so the default refusal applies.
 */
public final class VaultPermissionProvider implements PermissionProvider {

    /** Loads Vault's {@code Permission} type now, inside the {@code Integrations} guard (see the economy adapter). */
    public VaultPermissionProvider() {
        Permission.class.getName();
    }

    @Override
    public String name() {
        return "Vault permissions";
    }

    @Override
    public boolean available() {
        return Bukkit.getServicesManager().load(Permission.class) != null;
    }

    @Override
    public ProviderResult grant(UUID player, String node) {
        Permission permission = Bukkit.getServicesManager().load(Permission.class);
        if (permission == null) {
            return ProviderResult.failed("no permission plugin is registered with Vault");
        }
        OfflinePlayer account = Bukkit.getOfflinePlayer(player);
        return permission.playerAdd((String) null, account, node)
                ? ProviderResult.OK
                : ProviderResult.failed("Vault's permission plugin refused " + node);
    }

    @Override
    public ProviderResult revoke(UUID player, String node) {
        Permission permission = Bukkit.getServicesManager().load(Permission.class);
        if (permission == null) {
            return ProviderResult.failed("no permission plugin is registered with Vault");
        }
        OfflinePlayer account = Bukkit.getOfflinePlayer(player);
        return permission.playerRemove((String) null, account, node)
                ? ProviderResult.OK
                : ProviderResult.failed("Vault's permission plugin did not remove " + node);
    }
}
