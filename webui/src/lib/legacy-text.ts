export interface LegacyStyle {
  color?: string;
  bold: boolean;
  italic: boolean;
  underline: boolean;
  strikethrough: boolean;
  obfuscated: boolean;
}

export interface LegacySegment extends LegacyStyle {
  text: string;
}

const emptyStyle = (): LegacyStyle => ({
  bold: false, italic: false, underline: false, strikethrough: false, obfuscated: false,
});

function tokenAt(text: string, offset: number): { code: string; length: number } | undefined {
  if (text[offset] !== '&') return;
  const tail = text.slice(offset);
  const hex = /^&#([0-9a-f]{6})/i.exec(tail);
  if (hex) return { code: `#${hex[1]!.toLowerCase()}`, length: 8 };
  const repeated = /^&x((?:&[0-9a-f]){6})/i.exec(tail);
  if (repeated) return { code: `#${repeated[1]!.replaceAll('&', '').toLowerCase()}`, length: 14 };
  const code = text[offset + 1]?.toLowerCase();
  if (code && /^[0-9a-fklmnor]$/.test(code)) return { code, length: 2 };
}

function applyCode(style: LegacyStyle, code: string): LegacyStyle {
  if (code === 'r') return emptyStyle();
  if (/^[0-9a-f]$/.test(code) || code.startsWith('#')) return { ...emptyStyle(), color: code };
  const decoration = { k: 'obfuscated', l: 'bold', m: 'strikethrough', n: 'underline', o: 'italic' } as const;
  const key = decoration[code as keyof typeof decoration];
  return key ? { ...style, [key]: true } : style;
}

/** Mirrors ItemText's legacy-ampersand representation; unknown tokens remain literal text. */
export function parseLegacyText(text: string): LegacySegment[] {
  const segments: LegacySegment[] = [];
  let style = emptyStyle();
  let run = '';
  for (let offset = 0; offset < text.length;) {
    const token = tokenAt(text, offset);
    if (token) {
      if (run) segments.push({ ...style, text: run });
      run = '';
      style = applyCode(style, token.code);
      offset += token.length;
    } else {
      run += text[offset++]!;
    }
  }
  if (run) segments.push({ ...style, text: run });
  return segments;
}

export function plainLegacyText(text: string): string {
  return parseLegacyText(text).map((segment) => segment.text).join('');
}

function styleAt(text: string, end: number): LegacyStyle {
  let style = emptyStyle();
  for (let offset = 0; offset < end;) {
    const token = tokenAt(text, offset);
    if (token && offset + token.length <= end) {
      style = applyCode(style, token.code);
      offset += token.length;
    } else offset++;
  }
  return style;
}

function restoreStyle(style: LegacyStyle): string {
  return '&r' + (style.color ? `&${style.color}` : '') +
    (style.obfuscated ? '&k' : '') + (style.bold ? '&l' : '') +
    (style.strikethrough ? '&m' : '') + (style.underline ? '&n' : '') + (style.italic ? '&o' : '');
}

function formattedRun(text: string, code: string): string {
  let result = '';
  for (let offset = 0; offset < text.length;) {
    const token = tokenAt(text, offset);
    if (!token) {
      result += text[offset++]!;
      continue;
    }
    const color = /^[0-9a-f]$/.test(token.code) || token.code.startsWith('#');
    if (code !== 'r' && !(/^[0-9a-f]$/.test(code) && color)) {
      result += text.slice(offset, offset + token.length);
      // A legacy color/reset clears decorations; keep the requested style across it.
      if ((color && /^[klmno]$/.test(code)) || token.code === 'r') result += `&${code}`;
    }
    offset += token.length;
  }
  return result;
}

interface LegacySelection {
  text: string;
  start: number;
  end: number;
}

/** Change selected formatting without losing unknown tokens or restyling following text. */
export function formatLegacySelection(raw: string, start: number, end: number, code: string, multiline = false): LegacySelection {
  if (!/^[0-9a-fklmnor]$/i.test(code)) throw new Error('Unsupported legacy formatting control.');
  start = Math.max(0, Math.min(raw.length, start));
  end = Math.max(start, Math.min(raw.length, end));
  if (multiline) {
    let offset = 0;
    let delta = 0;
    let nextStart = start;
    let nextEnd = end;
    const lines = raw.split('\n').map((line) => {
      const from = Math.max(0, start - offset);
      const to = Math.min(line.length, end - offset);
      let text = line;
      if (from < to || (start === end && start >= offset && start <= offset + line.length)) {
        const edited = formatLegacySelection(line, from, to, code);
        text = edited.text;
        if (start >= offset && start <= offset + line.length) nextStart = offset + delta + edited.start;
        if (end >= offset && end <= offset + line.length) nextEnd = offset + delta + edited.end;
        delta += text.length - line.length;
      } else if (end >= offset && end <= offset + line.length) nextEnd = end + delta;
      offset += line.length + 1;
      return text;
    });
    return { text: lines.join('\n'), start: nextStart, end: nextEnd };
  }
  const prefix = `&${code.toLowerCase()}`;
  const suffix = start !== end && end < raw.length ? restoreStyle(styleAt(raw, end)) : '';
  const selected = formattedRun(raw.slice(start, end), code.toLowerCase());
  return {
    text: raw.slice(0, start) + prefix + selected + suffix + raw.slice(end),
    start: start + prefix.length,
    end: start + prefix.length + selected.length,
  };
}

export function editableLegacyText(value: unknown): string | null {
  if (value === undefined || value === null) return '';
  return typeof value === 'string' && !/[\r\n]/.test(value) ? value : null;
}

export function editableLegacyLines(value: unknown): string | null {
  if (value === undefined || value === null) return '';
  return Array.isArray(value) && value.every((line) => typeof line === 'string' && !/[\r\n]/.test(line))
    ? value.join('\n') : null;
}

export function legacyLines(raw: string): string[] {
  return raw === '' ? [] : raw.split('\n');
}
