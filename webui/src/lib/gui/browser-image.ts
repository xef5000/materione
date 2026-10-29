/**
 * The two places the GUI raster meets the browser's image stack: decoding a
 * pack texture into an `ArgbCanvas`, and encoding a canvas back into PNG bytes
 * for a save. Everything else in lib/gui is DOM-free.
 */
import { ArgbCanvas } from './raster/canvas';

export function decodeImage(base64: string): Promise<ArgbCanvas> {
  return new Promise((resolve, reject) => {
    const image = new Image();
    image.onload = () => {
      const scratch = document.createElement('canvas');
      scratch.width = image.naturalWidth;
      scratch.height = image.naturalHeight;
      const context = scratch.getContext('2d', { willReadFrequently: true })!;
      context.drawImage(image, 0, 0);
      const data = context.getImageData(0, 0, scratch.width, scratch.height);
      resolve(ArgbCanvas.fromRgba(scratch.width, scratch.height, data.data));
    };
    image.onerror = () => reject(new Error('not a readable image'));
    image.src = `data:image/png;base64,${base64}`;
  });
}

/** A canvas as RGBA `ImageData`, for drawing it onto a display canvas. */
export function toImageData(canvas: ArgbCanvas): ImageData {
  return new ImageData(canvas.toRgba() as Uint8ClampedArray<ArrayBuffer>, canvas.width, canvas.height);
}

export function encodePng(canvas: ArgbCanvas): Promise<Uint8Array> {
  const scratch = document.createElement('canvas');
  scratch.width = canvas.width;
  scratch.height = canvas.height;
  scratch.getContext('2d')!.putImageData(toImageData(canvas), 0, 0);
  return new Promise((resolve, reject) => {
    scratch.toBlob(async (blob) => {
      if (!blob) {
        reject(new Error('could not encode the PNG'));
        return;
      }
      resolve(new Uint8Array(await blob.arrayBuffer()));
    }, 'image/png');
  });
}

/** The largest side of an uploaded image: a chest screen is 176 x 222, so this is generous. */
export const MAX_UPLOAD_SIDE = 1024;
export const MAX_UPLOAD_BYTES = 2_000_000;

const PNG_SIGNATURE = [137, 80, 78, 71, 13, 10, 26, 10];

/**
 * An uploaded image file as the studio holds it: decoded pixels plus the PNG bytes to save. A PNG
 * keeps its original bytes (nothing is re-encoded, so no pixel changes on the way); any other format
 * the browser can read is converted. Anything else is refused with a sentence for the author.
 */
export async function readImageFile(file: File): Promise<{ canvas: ArgbCanvas; png: Uint8Array }> {
  if (file.size > MAX_UPLOAD_BYTES) throw new Error(`That image is larger than ${Math.round(MAX_UPLOAD_BYTES / 1_000_000)} MB.`);
  const bytes = new Uint8Array(await file.arrayBuffer());
  let bitmap: ImageBitmap;
  try {
    bitmap = await createImageBitmap(new Blob([bytes], { type: file.type || 'image/png' }));
  } catch {
    throw new Error('That file is not an image the browser can read (use PNG).');
  }
  if (bitmap.width > MAX_UPLOAD_SIDE || bitmap.height > MAX_UPLOAD_SIDE) {
    bitmap.close();
    throw new Error(`That image is larger than ${MAX_UPLOAD_SIDE} x ${MAX_UPLOAD_SIDE} pixels.`);
  }
  const scratch = document.createElement('canvas');
  scratch.width = bitmap.width;
  scratch.height = bitmap.height;
  const context = scratch.getContext('2d', { willReadFrequently: true })!;
  context.drawImage(bitmap, 0, 0);
  const data = context.getImageData(0, 0, scratch.width, scratch.height);
  bitmap.close();
  const canvas = ArgbCanvas.fromRgba(scratch.width, scratch.height, data.data);
  const isPng = PNG_SIGNATURE.every((value, index) => bytes[index] === value);
  return { canvas, png: isPng ? bytes : await encodePng(canvas) };
}
