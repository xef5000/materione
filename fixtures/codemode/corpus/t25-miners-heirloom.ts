// T25 Miner's heirloom (agent-benchmark.md section 3): a pickaxe with 2000 durability that is kept on death.
const p = m.pack("bench");
p.items.create("miners_heirloom", {
  material: "NETHERITE_PICKAXE",
  display_name: m.mm("<gold>Miner's Heirloom"),
  mechanics: [m.mechanics.durability({ max: 2000 }), m.mechanics.soulbound()],
});

m.summary("Adds the miner's heirloom: a netherite pickaxe with 2000 durability, soulbound.");
