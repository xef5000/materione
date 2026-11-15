// T26 Announcements (agent-benchmark.md section 3): every 5 minutes broadcast the next of four tips.
const p = m.pack("bench");
const tips = [
  "Use /kits for a starter kit.",
  "Vote daily for keys.",
  "Join our Discord: /discord",
  "Read /rules.",
];
const next = p.variables.create("tip_index", { scope: "server", type: "number", default: 0 });
const announce = p.rules.create("announce", { description: "Broadcast rotating server tips every 5 minutes" });
announce.rules.add(m.triggers.every({ minutes: 5 }), () => ({
  do: [
    m.steps.choose(
      tips.map((t, i) => ({
        if: m.expr`${next} == ${i}`,
        then: [m.actions.broadcast(`<aqua>[Tip] ${t}`)],
      })),
    ),
    m.actions.setVar({ var: next, op: "set", value: m.expr`(${next} + 1) % ${tips.length}` }),
  ],
}));
