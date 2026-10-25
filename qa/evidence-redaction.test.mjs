import assert from 'node:assert/strict';
import { test } from 'node:test';
import { redactEvidence } from './evidence-redaction.mjs';
import { sanitizeBrowserDiagnostic, sanitizeBrowserDiagnosticText } from '../webui/test-support/qa-browser.mjs';

test('redacts local and cloud editor capability URLs in text and JSON', () => {
  for (const origin of ['http://127.0.0.1:8092/', 'https://editor.example.test/']) {
    const capability = `${origin}?s=qa-session-identifier_123#qa-capability-token_456`;
    const text = `MATERIONE-EDITOR ${capability}\n${JSON.stringify({ reply: capability })}`;
    const redacted = redactEvidence(text);
    assert.ok(!redacted.includes('qa-session-identifier_123'));
    assert.ok(!redacted.includes('qa-capability-token_456'));
    assert.ok(redacted.includes(`${origin}?s=[REDACTED]#[REDACTED]`));
    assert.equal(redactEvidence(redacted), redacted);
  }
});

test('redacts path-style editor capability URLs in text and JSON', () => {
  const capability = 'https://editor.example.test/s/qa-session-code#qa-capability-key';
  const text = `open ${capability}\n${JSON.stringify({ url: capability })}`;
  const redacted = redactEvidence(text);
  assert.ok(!redacted.includes('qa-session-code'));
  assert.ok(!redacted.includes('qa-capability-key'));
  assert.ok(redacted.includes('https://editor.example.test/s/[REDACTED]#[REDACTED]'));
  assert.equal(redactEvidence(redacted), redacted);
});

test('sanitizes bounded browser handshake diagnostics without capability material', () => {
  const secrets = 'https://127.0.0.1:18089/?s=qa-session-identifier_123#qa-capability-token_456 /s/qa-path-code#qa-path-key deadbeef';
  const beforeScrub = sanitizeBrowserDiagnostic({
    addressCleared: false,
    readyState: 'interactive',
    approvalPromptVisible: true,
    bodyChars: 500,
    bodyText: secrets,
  });
  assert.equal(beforeScrub.addressCleared, false);
  assert.ok(!Object.hasOwn(beforeScrub, 'bodyText'));
  assert.ok(!JSON.stringify(beforeScrub).includes('qa-session-identifier_123'));

  const afterScrub = sanitizeBrowserDiagnostic({
    addressCleared: true,
    readyState: 'interactive',
    approvalPromptVisible: true,
    bodyChars: 500,
    bodyText: `Waiting for approval nonce deadbeef: ${secrets}`,
  });
  const serialized = JSON.stringify(afterScrub);
  for (const secret of ['qa-session-identifier_123', 'qa-capability-token_456', 'qa-path-code', 'qa-path-key', 'deadbeef']) {
    assert.ok(!serialized.includes(secret));
  }
  assert.ok(afterScrub.bodyText.length <= 360);
  assert.ok(sanitizeBrowserDiagnosticText(secrets, 80).length <= 80);
});

test('redacts session creation markers without hiding readiness', () => {
  assert.equal(
    redactEvidence('MTR-0031: editor session qa-session-identifier_123 created; awaiting browser'),
    'MTR-0031: editor session [REDACTED] created; awaiting browser',
  );
  assert.equal(redactEvidence('Editor session ready (local)'), 'Editor session ready (local)');
});

test('preserves ordinary URLs and handles session URLs without a fragment', () => {
  const ordinary = 'https://example.test/pack.zip#checksum http://localhost:8091/pack.zip';
  assert.equal(redactEvidence(ordinary), ordinary);
  assert.equal(
    redactEvidence('https://editor.example.test/?mode=local&s=qa-session-identifier_123'),
    'https://editor.example.test/?mode=local&s=[REDACTED]',
  );
});
