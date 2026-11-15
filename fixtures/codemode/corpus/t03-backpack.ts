// T03 Backpack (agent-benchmark.md section 3): a 3-row backpack item that keeps its contents.
// Passes via the registered `backpack` mechanic (the by-hand route requires CUSTODY).
const p = m.pack("bench");
p.items.create("traveler_pack", {
  material: "LEATHER",
  display_name: m.mm("Traveler's Pack"),
  mechanics: [m.mechanics.backpack({ rows: 3, title: "Traveler's Pack" })],
});

m.summary("Adds a 3-row Traveler's Pack backpack item.");
