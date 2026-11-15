// T17 Leaderboard (agent-benchmark.md section 3): /top shows the ten players with the most kills.
const p = m.pack("bench");
const kills = p.variables.create("kills", { scope: "player", type: "number", default: 0 });
const counter = p.rules.create("count_kills", { description: "Count player kills" });
counter.rules.add(m.triggers.entityKill(), () => ({
  do: [m.actions.setVar({ var: kills, op: "add", value: "1" })],
}));

p.guis.create("top", {
  container: "chest_3",
  title: "Top kills",
  open: { commands: ["top"] },
  layers: [
    m.guiComponents.list({
      id: "top_players",
      slots: [10, 11, 12, 13, 14, 15, 16, 19, 20, 21],
      source: m.listSources.ranking({ variable: kills, order: "desc", limit: 10 }),
      entry: { item: "head", name: "${entry.name}: ${entry.value}" },
    }),
  ],
});
