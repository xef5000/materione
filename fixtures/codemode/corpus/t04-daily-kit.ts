// T04 Kits (agent-benchmark.md section 3): /kits menu with Starter (once) and Daily (every 24 h).
// `now()` is an expression function now (the clock work, issue 328), so the paper gap [TIME] is closed.
const p = m.pack("bench");
const claimed = p.variables.create("kit_starter_claimed", { scope: "player", type: "boolean", default: false });
const daily = p.variables.create("kit_daily_at", { scope: "player", type: "number", default: 0 });
const kits = {
  starter: ["stone_sword", "bread"],
  daily: ["golden_apple", "experience_bottle"],
};

const menu = p.guis.create("kits", {
  container: "chest_3",
  title: "Kits",
  open: { commands: ["kits"] },
  layers: [
    m.guiComponents.button({ id: "starter_kit", x: 34, y: 30, width: 36, height: 20 }),
    m.guiComponents.button({ id: "daily_kit", x: 98, y: 30, width: 36, height: 20 }),
  ],
});

menu.rules.add(m.triggers.guiClick({ layer: "starter_kit" }), () => ({
  do: [
    m.steps.if(m.expr`!${claimed}`, {
      else: [m.actions.message("<red>Already claimed."), m.steps.stop()],
    }),
    ...kits.starter.map((id) => m.actions.giveItem({ item: `minecraft:${id}`, amount: 1 })),
    m.actions.setVar({ var: claimed, op: "toggle" }),
  ],
}));

menu.rules.add(m.triggers.guiClick({ layer: "daily_kit" }), () => ({
  do: [
    m.steps.if(m.expr`${m.fn.now()} - ${daily} >= 86400`, {
      else: [m.actions.message("<red>Come back later."), m.steps.stop()],
    }),
    ...kits.daily.map((id) => m.actions.giveItem({ item: `minecraft:${id}`, amount: 1 })),
    m.actions.setVar({ var: daily, op: "set", value: m.expr`${m.fn.now()}` }),
  ],
}));

m.summary("Adds a /kits GUI with a one-time starter kit and a 24-hour daily kit.");
