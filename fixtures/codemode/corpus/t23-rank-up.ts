// T23 Rank-up (agent-benchmark.md section 3): /rankup pays 10000 coins for the next rank and announces it.
const p = m.pack("bench");
const ranks = [
  { id: "member", next: "knight", cost: 10_000 },
  { id: "knight", next: "lord", cost: 50_000 },
];

const menu = p.guis.create("rankup", {
  container: "chest_3",
  title: "Rank up",
  open: { commands: ["rankup"] },
  layers: [m.guiComponents.button({ id: "rankup_btn", x: 70, y: 30, width: 36, height: 20 })],
});

menu.rules.add(m.triggers.guiClick({ layer: "rankup_btn" }), (ctx) => ({
  do: [
    m.steps.choose(
      ranks.map((r) => ({
        if: m.conditions.all([
          m.conditions.hasPermission(`rank.${r.id}`),
          m.conditions.not(m.conditions.hasPermission(`rank.${r.next}`)),
        ]),
        then: [
          m.steps.require([m.actions.takeMoney(r.cost)], {
            else: [m.actions.message(`<red>You need ${r.cost} coins.`)],
          }),
          m.actions.givePermission(`rank.${r.next}`),
          m.actions.broadcast(m.expr`"<gold>" + ${ctx.player.name} + ${" is now a " + r.next + "!"}`),
          m.actions.guiClose(),
        ],
      })),
      { else: [m.actions.message("<gray>You are at the highest rank.")] },
    ),
  ],
}));

m.summary("Adds a /rankup GUI that promotes players through the rank ladder for coins.");
