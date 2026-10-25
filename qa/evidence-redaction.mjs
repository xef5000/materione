export function redactEvidence(text) {
  return text
    .replace(
      /(https?:\/\/[^\s"'<>\\]*[?&]s=)[A-Za-z0-9_-]+(#[A-Za-z0-9_-]+)?/g,
      (_match, prefix, fragment) => `${prefix}[REDACTED]${fragment ? '#[REDACTED]' : ''}`,
    )
    .replace(/(\beditor session )[A-Za-z0-9_-]{20,}/g, '$1[REDACTED]');
}
