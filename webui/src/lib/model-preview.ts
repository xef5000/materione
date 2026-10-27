import { assetOps, unwrap } from './api';
import { token } from './tokens';
import type { RpcChannel } from './api';
import {
  flatLayers,
  projectFaces,
  resolveModel,
  type ModelJson,
  type ResolvedModel,
  type TextureSource,
} from './model-render';

/** Summary render input (web.md §4.2): a model asset path or the compiled inline model. */
export interface RenderSpec {
  pack: string;
  path?: string;
  model?: ModelJson;
}

/** Canvas pixels per preview; textures stay crisp up to 128 CSS px. */
export const PREVIEW_SIZE = 128;
const MAX_CACHED_PREVIEWS = 128;
const MAX_CACHED_TEXTURES = 128;
const MAX_CONCURRENT_RENDERS = 3;
const MAX_MODEL_BYTES = 256 * 1024;

const previews = new WeakMap<RpcChannel, Map<string, Promise<string>>>();
const textures = new WeakMap<RpcChannel, Map<string, Promise<CanvasImageSource & { width: number; height: number }>>>();
const waiting: (() => void)[] = [];
let activeRenders = 0;

/**
 * Draw a resource's model as an inventory-style PNG data URL. Rejects when
 * the model cannot be drawn (unknown vanilla geometry, unreadable assets);
 * callers then fall back to the static visual. Results are cached per
 * channel, spec and revision.
 */
export function renderModelPreview(
  channel: RpcChannel,
  spec: RenderSpec,
  packs: readonly string[],
  version = 0,
): Promise<string> {
  let cache = previews.get(channel);
  if (!cache) previews.set(channel, cache = new Map());
  const key = JSON.stringify([spec.pack, spec.path ?? null, spec.model ?? null, [...packs], version]);
  const existing = cache.get(key);
  if (existing) {
    cache.delete(key);
    cache.set(key, existing);
    return existing;
  }
  const rendering = limited(() => paint(channel, spec, packs, version));
  rendering.catch(() => cache.delete(key));
  cache.set(key, rendering);
  while (cache.size > MAX_CACHED_PREVIEWS) cache.delete(cache.keys().next().value!);
  return rendering;
}

async function paint(channel: RpcChannel, spec: RenderSpec, packs: readonly string[], version: number): Promise<string> {
  const root = spec.model ?? (spec.path ? await readModel(channel, spec.pack, spec.path) : undefined);
  if (!root) throw new Error('Nothing to render.');
  const model = await resolveModel(root, packs, {
    readModel: (pack, path) => readModel(channel, pack, path),
  });
  const canvas = document.createElement('canvas');
  canvas.width = PREVIEW_SIZE;
  canvas.height = PREVIEW_SIZE;
  const context = canvas.getContext('2d');
  if (!context) throw new Error('Canvas is unavailable.');
  context.imageSmoothingEnabled = false;
  if (model.kind === 'flat') {
    await paintFlat(context, model, packs, channel, version);
  } else {
    await paintElements(context, model, packs, channel, version);
  }
  return canvas.toDataURL('image/png');
}

async function paintFlat(
  context: CanvasRenderingContext2D, model: ResolvedModel, packs: readonly string[],
  channel: RpcChannel, version: number,
): Promise<void> {
  for (const layer of flatLayers(model, packs)) {
    const frame = await textureFrame(channel, layer, version, 1);
    context.drawImage(frame, 0, 0, frame.width, frame.height, 0, 0, PREVIEW_SIZE, PREVIEW_SIZE);
  }
}

async function paintElements(
  context: CanvasRenderingContext2D, model: ResolvedModel, packs: readonly string[],
  channel: RpcChannel, version: number,
): Promise<void> {
  const faces = projectFaces(model, packs, PREVIEW_SIZE);
  if (!faces.length) throw new Error('The model has no visible faces.');
  const frames = await Promise.all(faces.map((face) => textureFrame(channel, face.texture, version, face.shade)));
  faces.forEach((face, index) => {
    const frame = frames[index]!;
    context.save();
    context.beginPath();
    // Grow each face by a fraction of a pixel so anti-aliased clip edges don't leave seams.
    const cx = face.corners.reduce((sum, [x]) => sum + x, 0) / 4;
    const cy = face.corners.reduce((sum, [, y]) => sum + y, 0) / 4;
    face.corners.forEach(([x, y], corner) => {
      const length = Math.hypot(x - cx, y - cy) || 1;
      const px = x + ((x - cx) / length) * 0.4;
      const py = y + ((y - cy) / length) * 0.4;
      if (corner === 0) context.moveTo(px, py);
      else context.lineTo(px, py);
    });
    context.closePath();
    context.clip();
    context.setTransform(...face.transform);
    context.drawImage(frame, 0, 0, frame.width, frame.height, 0, 0, 16, 16);
    context.restore();
  });
}

/** The texture's first animation frame, pre-darkened to the face's GUI shade. */
async function textureFrame(
  channel: RpcChannel, source: TextureSource, version: number, shade: number,
): Promise<HTMLCanvasElement> {
  const image = 'placeholder' in source ? placeholder() : await readTexture(channel, source.pack, source.path, version);
  const width = image.width;
  // Animated strips stack square frames vertically (.mcmeta); show the first.
  const height = image.height > width && image.height % width === 0 ? width : image.height;
  const frame = document.createElement('canvas');
  frame.width = width;
  frame.height = height;
  const context = frame.getContext('2d')!;
  context.drawImage(image, 0, 0, width, height, 0, 0, width, height);
  if (shade < 1) {
    context.globalCompositeOperation = 'source-atop';
    context.globalAlpha = 1 - shade;
    context.fillStyle = token('--preview-shade');
    context.fillRect(0, 0, width, height);
    context.globalAlpha = 1;
  }
  return frame;
}

let placeholderCanvas: HTMLCanvasElement | undefined;

/** Neutral checker for vanilla or unreadable textures (we hold no Mojang textures). */
function placeholder(): HTMLCanvasElement {
  if (placeholderCanvas) return placeholderCanvas;
  placeholderCanvas = document.createElement('canvas');
  placeholderCanvas.width = 2;
  placeholderCanvas.height = 2;
  const context = placeholderCanvas.getContext('2d')!;
  context.fillStyle = token('--preview-missing-a');
  context.fillRect(0, 0, 2, 2);
  context.fillStyle = token('--preview-missing-b');
  context.fillRect(0, 0, 1, 1);
  context.fillRect(1, 1, 1, 1);
  return placeholderCanvas;
}

function readTexture(channel: RpcChannel, pack: string, path: string, version: number) {
  let cache = textures.get(channel);
  if (!cache) textures.set(channel, cache = new Map());
  const key = `${pack}\u0000${path}\u0000${version}`;
  const existing = cache.get(key);
  if (existing) return existing.catch(() => placeholder());
  const loading = (async () => {
    const data = await readAsset(channel, pack, path);
    const image = new Image();
    image.src = `data:image/png;base64,${data}`;
    await image.decode();
    return image;
  })();
  cache.set(key, loading);
  while (cache.size > MAX_CACHED_TEXTURES) cache.delete(cache.keys().next().value!);
  // A missing texture shows the placeholder rather than failing the whole model.
  return loading.catch(() => placeholder());
}

async function readModel(channel: RpcChannel, pack: string, path: string): Promise<ModelJson> {
  const data = await readAsset(channel, pack, path);
  if (data.length > (MAX_MODEL_BYTES * 4) / 3 + 4) throw new Error('Model file is too large to preview.');
  const bytes = Uint8Array.from(atob(data), (char) => char.charCodeAt(0));
  const parsed: unknown = JSON.parse(new TextDecoder().decode(bytes));
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error('Model is not a JSON object.');
  return parsed as ModelJson;
}

async function readAsset(channel: RpcChannel, pack: string, path: string): Promise<string> {
  const payload = unwrap(await assetOps.get(channel, pack, path));
  const data = typeof payload.data === 'string' ? payload.data.trim() : '';
  if (!data) throw new Error(`Asset ${path} is empty or missing.`);
  return data;
}

async function limited<T>(run: () => Promise<T>): Promise<T> {
  if (activeRenders >= MAX_CONCURRENT_RENDERS) {
    await new Promise<void>((resolve) => waiting.push(resolve));
  }
  activeRenders += 1;
  try {
    return await run();
  } finally {
    activeRenders -= 1;
    waiting.shift()?.();
  }
}
