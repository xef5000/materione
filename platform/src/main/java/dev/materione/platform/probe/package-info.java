/**
 * The platform probe (plugin architecture §1.1, §1.3): resolves the server
 * flavor and version once, at bootstrap, before anything version-sensitive
 * runs. Detects the Folia <em>server flavor</em> (Materione's own scheduling
 * is native, nothing to detect "being present") plus optional Geyser/Via
 * hooks — recorded, used opportunistically, never required.
 */
package dev.materione.platform.probe;
