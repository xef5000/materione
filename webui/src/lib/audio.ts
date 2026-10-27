/**
 * Small pure helpers for the sound studio (issue 153): the waveform a source
 * row draws, its duration label and base64 asset bytes. Decoding itself needs
 * the browser's AudioContext and lives in the component.
 */

/** Peak amplitude (0..1) of `bins` equal slices of one channel. */
export function peaksOf(samples: ArrayLike<number>, bins: number): number[] {
  const count = Math.max(1, Math.floor(bins));
  const peaks = new Array<number>(count).fill(0);
  const length = samples.length;
  if (length === 0) return peaks;
  const per = length / count;
  for (let bin = 0; bin < count; bin++) {
    const start = Math.floor(bin * per);
    const end = Math.max(start + 1, Math.min(length, Math.floor((bin + 1) * per)));
    let peak = 0;
    for (let index = start; index < end; index++) {
      const value = Math.abs(samples[index] ?? 0);
      if (value > peak) peak = value;
    }
    peaks[bin] = Math.min(1, peak);
  }
  return peaks;
}

/** `3.2 s` under a minute, `1:05` from a minute up; empty for an unreadable length. */
export function formatDuration(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds < 0) return '';
  if (seconds < 60) return `${(Math.round(seconds * 10) / 10).toString()} s`;
  const whole = Math.round(seconds);
  return `${Math.floor(whole / 60)}:${String(whole % 60).padStart(2, '0')}`;
}

/** Bytes of a base64 string as the ArrayBuffer `decodeAudioData` takes. */
export function bytesFromBase64(base64: string): ArrayBuffer {
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index++) bytes[index] = binary.charCodeAt(index);
  return bytes.buffer;
}
