/**
 * Shared test infrastructure (module {@code testing}, build infrastructure —
 * not a product module). ArchUnit rules live in testFixtures so every module
 * enforces the same boundaries; the golden-file helper backs the fixture
 * corpus (roadmap rule 2). Nothing here depends on product code.
 */
package dev.materione.testing;
