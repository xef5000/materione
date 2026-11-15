// F4 Rename permission (agent-benchmark.md section 2): "Rename the permission to rpg.bloodfang."
// Reads the pack's GUIs, finds any whose open.permission is rpg.weapons, and renames it to rpg.bloodfang.
const rows = await m.read.resources({ pack: "bench", type: "gui" });
const updated: string[] = [];
for (const row of rows) {
  const view = await m.read.get(row.ref);
  const open = view.fields.open as { permission?: string } | undefined;
  if (open?.permission === "rpg.weapons") {
    m.edit(row.ref).set({ open: { ...open, permission: "rpg.bloodfang" } });
    updated.push(row.ref.id);
  }
}

m.summary(`Renamed rpg.weapons to rpg.bloodfang on ${updated.length} resource(s).`);
return updated;
