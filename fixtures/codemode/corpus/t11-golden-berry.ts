// T11 Golden berry (agent-benchmark.md section 3): food that gives regeneration and speed for 5 seconds.
const p = m.pack("bench");
const berry = p.items.create("golden_berry", {
  material: "SWEET_BERRIES",
  display_name: m.mm("<gold>Golden Berry"),
  texture: m.ref.asset("textures/item/golden_berry.png"),
  mechanics: [m.mechanics.food({ nutrition: 4, saturation: 6 })],
});

berry.rules.add(m.triggers.consume(), () => ({
  do: [
    m.actions.potion({ type: "regeneration", duration: 100, amplifier: 1 }),
    m.actions.potion({ type: "speed", duration: 100 }),
  ],
}));

m.summary("Adds the golden berry: eating it gives regeneration II and speed for five seconds.");
