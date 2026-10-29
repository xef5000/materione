/**
 * The client's own font, as far as the studio can know it (gui.md sections 4 and 9.4). A `default`
 * text run references the vanilla ASCII bitmap by resource location and nothing of it is
 * redistributed: the studio never has its pixels, so the preview draws the bundled Minecraftia face
 * and says so ("approximate preview"). The advances are not approximate: they are the vanilla
 * widths from `fonts/vanilla-ascii/advances.txt` (`vanilla-ascii.ts`), the same numbers the compiler
 * uses to place the next run.
 */

/** The vanilla ASCII cell is 8 px high; a `default` run must start on row 5 or lower. */
export const DEFAULT_CELL_HEIGHT = 8;

export { defaultAdvance, measureDefault } from './vanilla-ascii';
