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
}
