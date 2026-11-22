// The soft-depend adapters (logic.md §4.5, build.md §3). Compiled against the server's optional
// plugin APIs and the Paper API, never shaded: Vault and LuckPerms are supplied by the server
// at runtime, and an absent plugin means its adapter is never loaded (Integrations guards every
// reference). Depends on api only among Materione modules; plugin/ wires it in.
plugins {
    id("materione.paper-conventions")
}

dependencies {
    api(project(":api"))
    api(project(":core"))
    compileOnly(libs.vault.api)
    compileOnly(libs.luckperms.api)
    // The protection adapters (docs/architecture/protection.md section 9). Non-transitive: only the plugin's own API artifacts
    // are on the compile classpath, never their dependency trees (the server supplies the real plugin at run time). Adapters
    // that touch nothing outside these classes link or fail inside the integration guard, never in the server.
    for (api in listOf(
        libs.worldguard.bukkit, libs.worldguard.core, libs.worldedit.bukkit, libs.worldedit.core,
        libs.griefprevention, libs.towny, libs.plotsquared.core, libs.plotsquared.bukkit,
        libs.huskclaims.bukkit, libs.husktowns.bukkit, libs.bentobox,
    )) {
        compileOnly(api) { isTransitive = false }
    }
}
