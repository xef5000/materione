// T07 Daily reward (agent-benchmark.md section 3): /daily, one claim per day, a streak that resets if a day is missed.
// `now()` is shipped by the clock work (issue 328), closing [TIME].
const p = m.pack("bench");
const last = p.variables.create("daily_day", { scope: "player", type: "number", default: 0 });
const streak = p.variables.create("daily_streak", { scope: "player", type: "number", default: 0 });

const menu = p.guis.create("daily", {
  container: "chest_3",
  title: "Daily reward",
  open: { commands: ["daily"] },
  layers: [m.guiComponents.button({ id: "claim", x: 70, y: 30, width: 36, height: 20 })],
});

menu.rules.add(m.triggers.guiClick({ layer: "claim" }), () => ({
  do: [
    m.actions.setVar({ var: "today", op: "set", value: m.expr`${m.fn.floor(m.expr`${m.fn.now()} / 86400`)}` }),
    m.steps.if(m.expr`vars.today > ${last}`, {
      else: [m.actions.message("<red>Come back tomorrow."), m.steps.stop()],
    }),
    m.actions.setVar({ var: streak, op: "set", value: m.expr`vars.today == ${last} + 1 ? min(${streak} + 1, 7) : 1` }),
    m.actions.setVar({ var: last, op: "set", value: m.expr`vars.today` }),
    m.steps.choose(
      [1, 2, 3, 4, 5, 6, 7].map((day) => ({
        if: m.expr`${streak} == ${day}`,
        then: [m.actions.giveMoney(100 * day)],
      })),
    ),
    m.actions.viewRefresh(),
  ],
}));

m.summary("Adds a /daily reward GUI with one claim per day and a streak up to 7 days.");
