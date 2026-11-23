// T19 Vending machine (agent-benchmark.md section 3): a furniture that opens a shop when right-clicked.
const p = m.pack("bench");
const shop = m.ref.gui("bench:shop");
const machine = p.furnitures.create("vending_machine", { model: m.ref.asset("models/vending_machine.json") });
machine.rules.add(m.triggers.click({ detail: "right" }), () => ({
  do: [m.actions.viewOpen(shop), m.actions.sound("block.note_block.chime")],
}));

m.summary("Adds a vending machine furniture that opens the shop GUI on right-click.");
