// T06 Quest (agent-benchmark.md section 3): kill 10 zombies, get 100 coins and a trophy; progress in /quests.
const p = m.pack("bench");
const kills = p.variables.create("quest_zombies", { scope: "player", type: "number", default: 0 });
const done = p.variables.create("quest_zombies_done", { scope: "player", type: "boolean", default: false });
const trophy = p.items.create("zombie_trophy", {
  material: "ZOMBIE_HEAD",
  display_name: m.mm("<gold>Zombie Slayer"),
});

const quest = p.rules.create("quest_zombies", { description: "Kill 10 zombies for 100 coins and a trophy" });
quest.rules.add(m.triggers.entityKill({ type: "zombie" }), () => ({
  if: m.expr`!${done}`,
  do: [
    m.actions.setVar({ var: kills, op: "add", value: m.expr`1` }),
    m.steps.if(m.expr`${kills} >= 10`, {
      then: [
        m.actions.giveMoney(100),
        m.actions.giveItem({ item: trophy, amount: 1 }),
        m.actions.setVar({ var: done, op: "toggle" }),
        m.actions.title("<gold>Quest complete"),
      ],
    }),
  ],
}));

p.guis.create("quests", {
  container: "chest_3",
  title: "Quests",
  open: { commands: ["quests"] },
  layers: [
    m.guiComponents.text({
      id: "zombie_progress",
      x: 8,
      y: 6,
      text: "Zombies: ${min(vars.player.bench.quest_zombies, 10)} / 10",
    }),
  ],
});

m.summary("Adds the zombie slayer quest rule, trophy item, and /quests progress menu.");
