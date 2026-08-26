import { describe, expect, it } from 'vitest';
import { Signal } from '../src/lib/events';

describe('Signal', () => {
  it('fans one emission out to every listener', () => {
    const signal = new Signal<number>();
    const seen: number[] = [];
    const off1 = signal.add((v) => seen.push(v * 2));
    signal.add((v) => seen.push(v * 10));

    signal.emit(3);
    expect(seen).toEqual([6, 30]);

    off1();
    signal.emit(1);
    expect(seen).toEqual([6, 30, 10]);
  });

  it('unsubscribe is idempotent and clear empties everything', () => {
    const signal = new Signal<string>();
    let calls = 0;
    const off = signal.add(() => calls++);
    off();
    off();

    signal.emit('x');
    expect(calls).toBe(0);

    signal.add(() => calls++);
    signal.clear();
    signal.emit('y');
    expect(calls).toBe(0);
  });
});
