// T27 /discord (agent-benchmark.md section 3): a command that prints the invite link.
// A command is content since #330 (logic.md section 40): `p.commands`, one document with its names and the flow it runs.
const p = m.pack("bench");
p.commands.create("discord", {
  names: ["discord"],
  do: [m.actions.message("<blue>Join us: <u>discord.gg/example</u>")],
});

m.summary("Adds a /discord command that prints the invite link to the player.");
