import assert from 'node:assert/strict';
import { test } from 'node:test';
import { redactEvidence } from './evidence-redaction.mjs';

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
