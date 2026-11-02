/** DebugBridge runCommand expects the command body, without the chat slash. */
export function commandBody(input) {
  if (typeof input !== 'string' || /[\r\n]/.test(input)) {
    throw new Error('command must be a single-line string');
  }
  const text = input.trim();
  const body = text.startsWith('/') ? text.slice(1) : text;
  if (!body || body.startsWith('/') || /^\s/.test(body)) {
    throw new Error('command must have a nonempty body and at most one leading slash');
  }
  return body;
}
