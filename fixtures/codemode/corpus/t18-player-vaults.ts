// T18 Player vaults (agent-benchmark.md section 3): /pv <1-3> opens private storage that persists.
const p = m.pack("bench");
p.guis.create("vault", {
  container: "chest_6",
  title: "Vault",
  open: { commands: ["pv"], args: { n: { type: "integer", min: 1, max: 3 } }, permission: "vaults.use" },
  layers: [
    m.guiComponents.slots({
      id: "vault_slots",
      region: "container",
      input: true,
      durable: "required",
      keep: { holder: "vault:${player.uuid}:${gui.args.n}" },
    }),
  ],
});
