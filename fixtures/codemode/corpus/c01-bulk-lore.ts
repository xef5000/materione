// C01 Bulk edit (corpus-only: no task of agent-benchmark.md reads and then edits what it found). It exercises the one
// async half of the SDK end to end: await a read, stage an edit for each stored resource it returned, and let the seal
// resolve each one's path and revision. A hand-written pack is not touched except through these edits.
const rows = await m.read.resources({ pack: "sample", type: "item", query: "ruby" });
for (const row of rows) {
  m.edit(row.ref).set({ lore: [m.mm("<gray>Kept for the harvest festival")] });
}

m.summary(`Adds a festival lore line to ${rows.length} ruby items.`);
return rows.map((row) => row.id);
