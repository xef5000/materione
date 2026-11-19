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
    api(project(":integrations-excellenteconomy"))
    compileOnly(libs.vault.api)
    compileOnly(libs.luckperms.api)
    compileOnly(libs.playerpoints.api)
    compileOnly(libs.ultraeconomy.api)
    compileOnly(libs.tokenmanager.api) { isTransitive = false }
    compileOnly(libs.rediseconomy.api) { isTransitive = false }
}
