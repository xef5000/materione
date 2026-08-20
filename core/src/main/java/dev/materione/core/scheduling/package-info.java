/**
 * The scheduling gateway (plugin architecture §2) — the ONLY code allowed to
 * touch Bukkit schedulers, enforced by an ArchUnit ban test. Routes through
 * Paper's native region/entity/async schedulers, which dispatch correctly on
 * both Paper (main thread) and Folia (region threads).
 */
package dev.materione.core.scheduling;
