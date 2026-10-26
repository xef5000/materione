/**
 * The recorded server the demo replays (web.md §6, cloud landing). The JVM
 * test `EditorDemoSnapshotTest` produces it from the real editor op set over
 * the `sample` and `world` fixture packs, so every schema, catalog row,
 * document, texture and model in the demo is what a live server with those
 * packs answers. Never edit the JSON by hand: regenerate it.
 */
import type { AssetRef, ResourceRef, ResourceSummary, TypeInfo } from '../api';

export interface SnapshotDocument {
  raw: string;
  rev: number;
  sha256: string;
  parsed: Record<string, unknown>;
  computed?: Record<string, unknown>;
}

export interface SnapshotAsset extends AssetRef {
  data: string;
}

export interface SnapshotPack {
  name: string;
  folders: string[];
  resources: ResourceRef[];
  summaries: ResourceSummary[];
  documents: Record<string, SnapshotDocument>;
  assets: SnapshotAsset[];
}

export interface DemoSnapshot {
  format: number;
  session: Record<string, unknown>;
  describe: { types: TypeInfo[]; behavior: Record<string, unknown> };
  types: TypeInfo[];
  diagnostics: Record<string, unknown>[];
  delivery: { publishedSha256: string; candidateSha256: string };
  settings: { raw: string; sha256: string; parsed: Record<string, unknown> };
  packs: SnapshotPack[];
  scaffoldId: string;
  scaffolds: Record<string, string>;
  reload: { itemsLive: number; quarantined: number };
}

/** Loaded on demand: the snapshot is the bulk of the demo chunk. */
export async function loadSnapshot(): Promise<DemoSnapshot> {
  const module = await import('../../../../fixtures/golden/editor-demo-snapshot.json');
  return structuredClone(module.default) as unknown as DemoSnapshot;
}
