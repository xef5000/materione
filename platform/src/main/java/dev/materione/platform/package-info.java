/**
 * Platform isolation (module {@code platform}, master §10): the only module
 * aware that server versions exist. Everything version-sensitive — the probe,
 * the capability descriptor, future version adapters — lives here. Content,
 * behavior, and pack code consume capabilities, never versions.
 */
package dev.materione.platform;
