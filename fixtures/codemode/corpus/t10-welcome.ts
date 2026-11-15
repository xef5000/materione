// T10 Welcome (agent-benchmark.md section 3): greet players on join; first join gets the starter kit and a broadcast.
const p = m.pack("bench");
const welcome = p.rules.create("welcome", { description: "Greet players on join and give first-join starter kit" });
welcome.rules.add(m.triggers.playerJoin(), (ctx) => ({
  do: [
    m.steps.if(m.expr`${ctx.player.first_join}`, {
      then: [
        m.actions.broadcast(m.expr`"<yellow>Welcome " + ${ctx.player.name} + " to the server!"`),
        m.actions.giveItem({ item: "minecraft:stone_sword", amount: 1 }),
      ],
      else: [m.actions.message(m.expr`"<gray>Welcome back, " + ${ctx.player.name}`)],
    }),
  ],
}));

m.summary("Adds a player join welcome rule with a first-join broadcast and stone sword.");
