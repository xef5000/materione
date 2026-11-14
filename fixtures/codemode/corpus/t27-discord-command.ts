// T27 /discord (agent-benchmark.md section 3): a command that prints the invite link.
// Commands are not content on today's server (gap CMD), so the SDK has no `commands` member: a type error.
const p = m.pack("bench");
p.commands.create("discord", { do: [m.actions.message("<blue>Join us: <u>discord.gg/example</u>")] });
