package dev.materione.api.pipeline;

import java.nio.file.Path;

/**
 * One pipeline run's operator-facing outcome (plugin.md §4): what was
 * scanned, what stayed valid, what quarantined, what the runtime looks like
 * afterwards, and which artifacts were produced.
 *
 * @param skipped          true when the run never happened (another reload in flight)
 * @param noop             true when the runtime diff was empty — identical pack
 *                         bytes alone do not make a no-op (M2 as-built)
 * @param resourcesValid   resources that passed every validation stage
 * @param quarantined      resources quarantined this run (fail-soft, D6)
 * @param itemsLive        live items after the apply
 * @param itemDefinitions  item definitions emitted into the pack
 * @param packSha256       sha256 of the compiled pack, null when none was built
 * @param packZip          the compiled pack artifact, null when none was built
 * @param stagedDatapackZip the staged world-datapack artifact; null when
 *                         nothing produced one
 * @param datapackSha256   sha256 of the staged datapack, null when none
 */
public record ReloadSummary(
        boolean skipped, boolean noop, int resourcesValid, int quarantined,
        int itemsLive, int itemDefinitions, String packSha256, Path packZip,
        Path stagedDatapackZip, String datapackSha256) {}
