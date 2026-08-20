/**
 * Storage: durable plugin state directories (master §7.1) and their format
 * markers. {@code storage/} is operator-backed-up state (the allocation
 * table lands here at M1), never a cache; {@code generated/} is disposable.
 */
package dev.materione.core.storage;
