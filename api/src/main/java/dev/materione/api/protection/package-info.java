/**
 * The protection port (docs/architecture/protection.md, logic.md section 43, #466): the one question "may this actor change this
 * place?", asked by every verb that changes the world on someone's behalf, answered by region and claim plugins through
 * adapters, and registrable by an addon through {@link dev.materione.api.protection.ProtectionApi}.
 */
package dev.materione.api.protection;
