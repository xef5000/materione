// F3 Scaled upgrade cost (agent-benchmark.md section 2): "The upgrade costs 500 times the level."
// Pre-seeded with a player-scoped Bloodfang GUI; adds a level-scaled upgrade rule to its upgrade button.
const menu = m.edit(m.ref.gui("bench:bloodfang_menu"));
menu.rules.add(m.triggers.guiClick({ layer: "upgrade" }), () => ({
  do: [
    m.steps.choose(
      [1, 2, 3, 4].map((lvl) => ({
        if: m.expr`vars.player.bench.bloodfang_level == ${lvl}`,
        then: [
          m.steps.require([m.actions.takeMoney(500 * lvl)], {
            else: [m.actions.message(`<red>You need ${500 * lvl} coins.`)],
          }),
          m.actions.setVar({ var: "bench:bloodfang_level", op: "add", value: m.expr`1` }),
          m.actions.guiRefresh(),
        ],
      })),
      { else: [m.actions.message("<gold>Bloodfang is already at max level.")] },
    ),
  ],
}));

m.summary("Scales the Bloodfang upgrade cost to 500 coins times the current level.");
