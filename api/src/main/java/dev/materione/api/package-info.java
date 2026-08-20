/**
 * Materione public contracts (module {@code api}, master architecture §10).
 *
 * <p>Depends on nothing internal; paper-api types appear in signatures only.
 * This package is the eventual addon-facing surface (registry SPI, diagnostics,
 * scheduling gateway, platform descriptor) — treat it as API: semver'd,
 * deprecate-then-remove.
 */
package dev.materione.api;
