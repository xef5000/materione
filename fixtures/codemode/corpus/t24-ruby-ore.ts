// T24 Ruby ore (agent-benchmark.md section 3): a custom block that drops a ruby and 5 XP, 10% chance of a second ruby.
const p = m.pack("bench");
const ruby = m.ref.item("sample:ruby");
const ore = p.blocks.create("ruby_ore", {
  display_name: m.mm("<red>Ruby Ore"),
  texture: m.ref.asset("textures/block/ruby_ore.png"),
  drops: ["sample:ruby"],
});

ore.rules.add(m.triggers.break(), () => ({
  do: [m.actions.giveItem({ item: ruby, amount: 1 }), m.actions.giveExp({ amount: 5, unit: "points" })],
}));
ore.rules.add(m.triggers.break(), () => ({
  do: [m.actions.giveItem({ item: ruby, amount: 1 }, { chance: 0.1 })],
}));

m.summary("Adds a Ruby Ore custom block that gives a ruby, 5 XP, and a 10% chance for a second ruby.");
