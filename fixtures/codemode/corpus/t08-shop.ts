// T08 Shop (agent-benchmark.md section 3): /shop with buy (left click) and sell (right click) for coins.
// The paywall shape of the engine (logic.md 2.2): `require` runs STEPS until one fails, then runs `else` and stops.
const p = m.pack("bench");
const stock = [
  { id: "diamond", buy: 250, sell: 100 },
  { id: "iron_ingot", buy: 40, sell: 15 },
  { id: "bread", buy: 5, sell: 1 },
];

const shop = p.guis.create("shop", {
  container: "chest_3",
  title: "Shop",
  open: { commands: ["shop"] },
  layers: stock.map((s, i) => m.guiComponents.button({ id: `buy_${s.id}`, x: 8 + i * 36, y: 20, width: 32, height: 20 })),
});

for (const s of stock) {
  shop.rules.add(m.triggers.guiClick({ layer: `buy_${s.id}`, click: "left" }), () => ({
    do: [
      // a condition guards with `if`; no room ends the run before any coin is taken
      m.steps.if(m.conditions.hasItemSpace(1), {
        else: [m.actions.message("<red>Your inventory is full."), m.steps.stop()],
      }),
      // take the coins, then give the item: if the take fails nothing is given
      m.steps.require([m.actions.takeMoney(s.buy), m.actions.giveItem({ item: `minecraft:${s.id}`, amount: 1 })], {
        else: [m.actions.message("<red>Not enough coins.")],
      }),
    ],
  }));
  shop.rules.add(m.triggers.guiClick({ layer: `buy_${s.id}`, click: "right" }), () => ({
    do: [
      m.steps.require([m.actions.takeItem({ item: `minecraft:${s.id}`, amount: 1 })], {
        else: [m.actions.message("<red>You have none to sell.")],
      }),
      m.actions.giveMoney(s.sell),
    ],
  }));
}

m.summary("Adds a shop GUI with three products: left click buys, right click sells.");
