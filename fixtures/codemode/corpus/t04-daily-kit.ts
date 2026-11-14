// T04 Kits, the daily kit (agent-benchmark.md section 3): once every 24 hours per player.
// `now()` is an expression function now (the clock work), so the paper gap [TIME] is closed.
const p = m.pack("bench");
const daily = p.variables.create("kit_daily_at", { scope: "player", type: "number", default: 0 });

const menu = p.guis.create("kits", {
  container: "chest_3",
  title: "Kits",
  open: { commands: ["kits"] },
  layers: [m.guiComponents.button({ id: "daily_kit", x: 70, y: 30, width: 36, height: 20 })],
});

menu.rules.add(m.triggers.guiClick({ layer: "daily_kit" }), () => ({
  do: [
    m.steps.if(m.expr`${m.fn.now()} - ${daily} >= 86400`, {
      else: [m.actions.message("<red>Come back later."), m.steps.stop()],
    }),
    m.actions.giveItem({ item: "minecraft:golden_apple", amount: 1 }),
    m.actions.giveItem({ item: "minecraft:experience_bottle", amount: 1 }),
    m.actions.setVar({ var: daily, op: "set", value: "${now()}" }),
  ],
}));

m.summary("Adds a daily kit button: a golden apple and an experience bottle, once every 24 hours per player.");
