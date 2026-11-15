// T13 Excavator pickaxe (agent-benchmark.md section 3): mines a 3x3 area.
const p = m.pack("bench");
const pick = p.items.create("excavator", {
  material: "DIAMOND_PICKAXE",
  display_name: m.mm("Excavator"),
});
pick.rules.add(m.triggers.break(), () => ({
  do: [m.actions.breakBlocks({ shape: [1, 1, 1], asPlayer: true, drops: true })],
}));
