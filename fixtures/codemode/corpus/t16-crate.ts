// T16 Crate (agent-benchmark.md section 3): a placed crate opened with a key gives one of five weighted rewards and
// announces the rare one. Authoring-time computation replaces a runtime feature: the cumulative weights are summed here,
// the server only draws one random() and walks a choose. A plain composition of shipped primitives is a pass.
const p = m.pack("bench");
const key = p.items.create("crate_key", { material: "TRIPWIRE_HOOK", display_name: m.mm("<gold>Crate Key") });
const rewards = [
  { w: 50, item: "minecraft:iron_ingot", n: 8 },
  { w: 30, item: "minecraft:gold_ingot", n: 4 },
  { w: 15, item: "minecraft:diamond", n: 2 },
  { w: 4, item: "minecraft:netherite_scrap", n: 1 },
  { w: 1, item: "minecraft:nether_star", n: 1, rare: true },
];
const crate = p.furnitures.create("crate", { model: m.ref.asset("models/crate.json") });
let floor = 0;
crate.rules.add(m.triggers.click({ detail: "right" }), () => ({
  do: [
    m.steps.require([m.actions.takeItem({ item: key, amount: 1 })], { else: [m.actions.message("<red>You need a Crate Key.")] }),
    m.actions.setVar({ var: "roll", op: "set", value: "${random(1, 100)}" }),
    m.steps.choose(
      rewards.map((r) => {
        floor += r.w;
        return {
          if: m.expr`vars.roll <= ${floor}`,
          then: [
            m.actions.giveItem({ item: r.item, amount: r.n }),
            ...(r.rare ? [m.actions.broadcast("<gold>${player.name} found a Nether Star!")] : []),
          ],
        };
      }) as [any, ...any[]],
    ),
  ],
}));

m.summary("Adds a crate and its key: right-clicking the crate with a key gives one of five weighted rewards.");
