/**
 * Assembly module (master §10, as amended by M0): {@code paper-plugin.yml}, the
 * JavaPlugin subclass, command registration, and the shaded distributable
 * jar. Wires core + platform together at bootstrap; nothing depends on this
 * module (it is a leaf, enforced by ArchUnit).
 */
package dev.materione.plugin;
