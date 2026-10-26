/**
 * Vanilla item icons for resources without a custom visual.
 *
 * Icons come from the third-party `minecraft-textures` npm package (GPL-3.0;
 * the images are Mojang's), loaded by the browser from jsDelivr at runtime.
 * Nothing is bundled into the editor or hosted by Materione. The package
 * version is pinned so a CDN update can never change what the editor shows.
 */

export const VANILLA_ICONS_PACKAGE = 'minecraft-textures@26.3.0';
const BASE_URL = `https://cdn.jsdelivr.net/npm/${VANILLA_ICONS_PACKAGE}/dist/textures`;

/** Manifests the pinned package ships, oldest first, from the D10 floor up. */
const MANIFEST_VERSIONS = [
  '1.21.4', '1.21.5', '1.21.6', '1.21.7', '1.21.9', '1.21.11', '26.1', '26.2', '26.3',
] as const;

interface Manifest {
  items?: Record<string, { texture?: unknown }>;
}

const manifests = new Map<string, Promise<Map<string, string>>>();

/**
 * The newest manifest not newer than the server's Minecraft version. An empty
 * or unparsable version (an older plugin) and a server newer than the pinned
 * package both use the newest manifest; items it lacks keep the placeholder.
 */
export function manifestVersionFor(minecraftVersion: string): string {
  const newest: string = MANIFEST_VERSIONS[MANIFEST_VERSIONS.length - 1]!;
  const server = parseVersion(minecraftVersion);
  if (!server) return newest;
  let chosen: string | undefined;
  for (const candidate of MANIFEST_VERSIONS) {
    if (compareVersions(parseVersion(candidate)!, server) <= 0) chosen = candidate;
  }
  return chosen ?? MANIFEST_VERSIONS[0];
}

/** `NETHERITE_SWORD` or `minecraft:netherite_sword` → `minecraft:netherite_sword`. */
export function vanillaItemId(material: string): string | undefined {
  const id = material.trim().toLowerCase().replace(/^minecraft:/, '');
  return /^[a-z0-9_./-]+$/.test(id) ? `minecraft:${id}` : undefined;
}

/** Resolve a material to its icon URL, or undefined when the package has none. */
export async function vanillaIconUrl(
  material: string,
  minecraftVersion: string,
  fetcher: typeof fetch = fetch,
): Promise<string | undefined> {
  const id = vanillaItemId(material);
  if (!id) return undefined;
  const icons = await loadManifest(manifestVersionFor(minecraftVersion), fetcher);
  const texture = icons.get(id);
  return texture ? `${BASE_URL}/assets/${texture}` : undefined;
}

/** Test seam: forget loaded manifests. */
export function resetVanillaIcons(): void {
  manifests.clear();
}

function loadManifest(version: string, fetcher: typeof fetch): Promise<Map<string, string>> {
  const existing = manifests.get(version);
  if (existing) return existing;
  const loading = fetcher(`${BASE_URL}/manifest/${version}.id.json`)
    .then((response) => {
      if (!response.ok) throw new Error(`Vanilla icon manifest ${version}: HTTP ${response.status}`);
      return response.json() as Promise<Manifest>;
    })
    .then((manifest) => {
      const icons = new Map<string, string>();
      for (const [id, entry] of Object.entries(manifest.items ?? {})) {
        // Hash file names only: a manifest can never point the editor elsewhere.
        if (typeof entry?.texture === 'string' && /^[0-9a-f]+\.png$/.test(entry.texture)) {
          icons.set(id, entry.texture);
        }
      }
      return icons;
    });
  // A failed load (offline, CDN outage) is retried on the next request.
  loading.catch(() => manifests.delete(version));
  manifests.set(version, loading);
  return loading;
}

function parseVersion(value: string): number[] | undefined {
  const match = /^(\d+)\.(\d+)(?:\.(\d+))?/.exec(value.trim());
  return match ? [Number(match[1]), Number(match[2]), Number(match[3] ?? 0)] : undefined;
}

function compareVersions(a: number[], b: number[]): number {
  for (let index = 0; index < 3; index++) {
    if (a[index] !== b[index]) return a[index]! - b[index]!;
  }
  return 0;
}
