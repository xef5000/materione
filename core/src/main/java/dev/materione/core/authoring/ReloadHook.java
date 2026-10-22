package dev.materione.core.authoring;

import dev.materione.api.auth.ActorContext;
import java.nio.file.Path;

/**
 * The live-preview seam after an authoring mutation (mirrors the editor's
 * {@code queueReload} semantics): every successful mutation calls it after
 * the journal write, and the host turns it into a scoped pipeline reload.
 *
 * <p>Convention, matching what each editor op queues today:
 * <ul>
 *   <li>{@code changedFile} is a regular file — the save loop's targeted
 *       reload of exactly the touched document ({@code ReloadScope.FileSet}).</li>
 *   <li>{@code changedFile} is a directory (the pack root) — a pack-wide
 *       operation: assets, model transactions, folder moves
 *       ({@code ReloadScope.OnePack}).</li>
 * </ul>
 */
@FunctionalInterface
public interface ReloadHook {

    /** Called after the mutation's journal write; never throws for content problems. */
    void afterChange(ActorContext actor, String pack, Path changedFile);
}
