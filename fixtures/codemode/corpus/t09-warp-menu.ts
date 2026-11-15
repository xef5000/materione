// T09 Warp menu (agent-benchmark.md section 3): /warps with spawn, mines, arena, and a link to the survival server.
const p = m.pack("bench");
const warps = [
  { id: "spawn", name: "Spawn", at: [0, 64, 0] as const },
  { id: "mines", name: "Mines", at: [120, 64, -40] as const },
  { id: "arena", name: "Arena", at: [-300, 70, 220] as const },
];

const menu = p.guis.create("warps", {
  container: "chest_3",
  title: "Warps",
  open: { commands: ["warps", "warp"] },
  layers: [
    ...warps.map((w, i) => m.guiComponents.button({ id: `warp_${w.id}`, x: 8 + i * 36, y: 20, width: 32, height: 20 })),
    m.guiComponents.button({ id: "survival_server", x: 116, y: 20, width: 32, height: 20 }),
  ],
});

for (const w of warps) {
  menu.rules.add(m.triggers.guiClick({ layer: `warp_${w.id}` }), () => ({
    do: [m.actions.guiClose(), m.actions.teleport({ x: w.at[0], y: w.at[1], z: w.at[2], world: "world" })],
  }));
}

menu.rules.add(m.triggers.guiClick({ layer: "survival_server" }), () => ({
  do: [m.actions.connect("survival")],
}));

m.summary("Adds a /warps menu with Spawn, Mines, Arena, and a Survival server transfer button.");
