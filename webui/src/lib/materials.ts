export function materialLabel(id: string): string {
  return id.toLowerCase().split('_').map((word) =>
    word ? word[0]!.toUpperCase() + word.slice(1) : '',
  ).join(' ');
}

export function matchingMaterials(choices: readonly string[], query: string): string[] {
  const words = query.toLowerCase().trim().split(/\s+/).filter(Boolean);
  return choices.filter((id) => {
    const searchable = `${id} ${materialLabel(id)}`.toLowerCase();
    return words.every((word) => searchable.includes(word));
  });
}
