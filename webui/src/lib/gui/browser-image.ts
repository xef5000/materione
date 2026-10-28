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
