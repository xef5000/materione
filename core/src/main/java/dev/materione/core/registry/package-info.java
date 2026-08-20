/**
 * Registry machinery (D5 — registries all the way down). Built-in content
 * types and addons register through the identical path; there is no internal
 * route. Window semantics: registration succeeds only while the window is
 * open (bootstrap or a reload's re-check).
 */
package dev.materione.core.registry;
