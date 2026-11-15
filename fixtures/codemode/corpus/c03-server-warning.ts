// C03 A server warning does not refuse (corpus only): the expression text names a variable by its reference spelling,
// which the expression language does not read. Only the interpolations of m.expr are typed, so this type-checks and
// stages; the dry run reports MTR-0022 as a warning and accepts the list. It keeps a recorded frame that carries the
// wire's "warn" severity (codemode-host-severity.spec.ts) now that T04 replays clean (#355).
const p = m.pack("bench");
const stamp = p.items.create("stamp", { material: "PAPER", display_name: m.mm("<gray>Stamp") });

stamp.rules.add(m.triggers.click({ detail: "right" }), () => ({
  if: m.expr`now() - bench:stamped_at >= 60`,
  do: [m.actions.message("<gray>Stamped.")],
}));

m.summary("Adds a stamp item whose rule condition is misspelled on purpose.");
