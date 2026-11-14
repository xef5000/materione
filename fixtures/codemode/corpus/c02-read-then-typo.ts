// C02 Read, then name an id that does not exist (corpus-only). The program reads the catalog and then edits an item by an id
// with a typo: the seal reports SDK-0007 at the line that named it, with the nearest ids, and no list is sent on.
const rows = await m.read.resources({ pack: "sample", type: "item" });
const first = rows.length > 0 ? rows[0].id : "none";
m.edit(m.ref.item("sample:ruby_sowrd")).set({ lore: [m.mm("<gray>Typo")] });
return first;
