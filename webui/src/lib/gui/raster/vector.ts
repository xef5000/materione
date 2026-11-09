/**
 * A shared raster vector (fixtures/gui/raster/README.md): an input JSON the
 * Java compiler and this renderer both read, and the PNG both must reproduce
 * pixel for pixel. This module is the TS side of the contract: it turns the
 * JSON into a `RasterScene` and runs the same `flattenRaster` the studio does.
 */
import { containerProfile, gridRows } from '../containers';
import { ArgbCanvas } from './canvas';
import { parseColor } from './color';
import { flattenRaster, type Layer, type RasterScene } from './flatten';

export interface VectorImage {
  width: number;
  height: number;
  /** Row-major, one `#RRGGBB` / `#RRGGBBAA` string per pixel. */
  pixels: string[];
}

export interface RasterVector {
  name: string;
  description?: string;
  canvas: { width: number; height: number };
  /**
   * The container the vector is drawn in (gui.md section 12.4), needed by `slots` layers: the slot grid is its profile's.
   * A vector with none is a 1-row chest, as before.
   */
  container?: string;
  /** Bitmaps by the `image:` reference layers use. */
  images?: Record<string, VectorImage>;
  layers: Layer[];
}

export function decodeVectorImage(image: VectorImage): ArgbCanvas {
  if (image.pixels.length !== image.width * image.height) {
    throw new Error(`vector image has ${image.pixels.length} pixels, expected ${image.width * image.height}`);
  }
  const canvas = new ArgbCanvas(image.width, image.height);
  image.pixels.forEach((text, index) => {
    const color = parseColor(text);
    if (color === null) throw new Error(`vector image pixel ${index} is not a color: ${text}`);
    canvas.pixels[index] = color;
  });
  return canvas;
}

export function renderVector(vector: RasterVector): ArgbCanvas {
  const images = new Map<string, ArgbCanvas>();
  for (const [reference, image] of Object.entries(vector.images ?? {})) {
    images.set(reference, decodeVectorImage(image));
  }
  const profile = containerProfile(vector.container ?? 'chest_1');
  const scene: RasterScene = {
    width: vector.canvas.width,
    height: vector.canvas.height,
    rows: profile ? gridRows(profile) : 6,
    ...(profile ? { container: profile } : {}),
    image: (reference) => images.get(reference) ?? null,
  };
  return flattenRaster(vector.layers, scene);
}
