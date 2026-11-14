// T29 Forge (agent-benchmark.md section 3): put a ruby sword and 3 rubies in, pay 200 coins, get the refined sword.
// The slots layer makes the container's slots input slots with crash-safe custody (durable: required); the click rule checks
// what sits in slots 11 and 13, takes the coins, consumes both stacks behind the save barrier, then gives the result.
const p = m.pack("bench");
const refined = p.items.create("refined_ruby_sword", { material: "NETHERITE_SWORD", display_name: m.mm("<red>Refined Ruby Sword") });
const forge = p.guis.create("forge", {
  container: "chest_3",
  title: "Forge",
  open: { commands: ["forge"] },
  layers: [
    m.guiComponents.slots({ id: "inputs", region: "container", input: true, durable: "required" }),
    m.guiComponents.button({ id: "forge_button", x: 134, y: 34, width: 18, height: 18 }),
  ],
});
forge.rules.add(m.triggers.guiClick({ layer: "forge_button" }), () => ({
  do: [
    m.steps.if(m.conditions.slotHas({ slot: 11, item: "sample:ruby_sword", amount: 1 }), {
      else: [m.actions.message("<red>Put a ruby sword in the first slot."), m.steps.stop()],
    }),
    m.steps.if(m.conditions.slotHas({ slot: 13, item: "sample:ruby", amount: 3 }), {
      else: [m.actions.message("<red>Put 3 rubies in the second slot."), m.steps.stop()],
    }),
    m.steps.require([m.actions.takeMoney(200)], { else: [m.actions.message("<red>You need 200 coins.")] }),
    m.actions.custodyConsume({ slot: 11, await: "saved" }),
    m.actions.custodyConsume({ slot: 13, await: "saved" }),
    m.actions.giveItem({ item: refined, amount: 1 }),
    m.actions.guiRefresh(),
  ],
}));

m.summary("Adds a forge GUI: a ruby sword and 3 rubies plus 200 coins become the refined ruby sword.");
