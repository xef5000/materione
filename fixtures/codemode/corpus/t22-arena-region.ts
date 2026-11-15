// T22 Arena region (agent-benchmark.md section 3): entering arena gives the PvP kit, leaving removes it.
const p = m.pack("bench");
const enter = p.rules.create("arena_enter", { description: "Give PvP kit when entering arena" });
enter.rules.add(m.triggers.regionEnter({ region: "arena" }), () => ({
  do: [
    m.actions.giveItem({ item: "minecraft:iron_sword", amount: 1 }),
    m.actions.message("<red>PvP is on."),
  ],
}));
